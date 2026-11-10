package br.com.datum.notification.infrastructure.config;

import br.com.datum.notification.application.CustomerCreatedEvent;
import br.com.datum.notification.application.NotificationService;
import br.com.datum.notification.infrastructure.persistence.NotificationStore;
import br.com.datum.notification.messaging.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("!smoke")
@EnableConfigurationProperties(NotificationConsumerProperties.class)
public class NotificationConfiguration {
    @Bean
    CustomerCreatedCodec customerCreatedCodec(ObjectMapper mapper) { return new CustomerCreatedCodec(mapper); }

    @Bean
    CreatedMessageMetadata createdMessageMetadata() { return new CreatedMessageMetadata(); }

    @Bean
    NotificationStore notificationStore(JdbcTemplate jdbc) { return new NotificationStore(jdbc); }

    @Bean
    NotificationService notificationService(NotificationStore store) { return new NotificationService(store); }

    @Bean
    NotificationRetrySender notificationRetrySender(RabbitTemplate template, NotificationConsumerProperties properties) {
        if (!(template.getConnectionFactory() instanceof CachingConnectionFactory factory)) {
            throw new IllegalStateException("Retry exige connection factory com confirms correlacionados.");
        }
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        template.setMandatory(true);
        return new ConfirmedNotificationRetrySender(template, properties);
    }

    @Bean
    NotificationChannelRecovery notificationChannelRecovery(NotificationConsumerProperties properties) {
        return channel -> {
            try {
                Thread.sleep(properties.recoveryDelayMs());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (channel.isOpen()) channel.abort();
        };
    }

    @Bean
    CreatedDeliveryHandler createdDeliveryHandler(CustomerCreatedCodec codec, CreatedMessageMetadata metadata,
            NotificationService service, NotificationRetrySender retry, NotificationChannelRecovery recovery, br.com.datum.notification.observability.Telemetry telemetry) {
        return new CreatedDeliveryHandler(codec, metadata, service, retry, recovery, telemetry);
    }

    @Bean
    @ConditionalOnProperty(name = "notification.consumer.enabled", havingValue = "true", matchIfMissing = true)
    SimpleMessageListenerContainer createdListener(ConnectionFactory factory, CreatedDeliveryHandler handler,
                                                   NotificationConsumerProperties properties) {
        var container = new SimpleMessageListenerContainer(factory);
        container.setQueueNames(CustomerCreatedEvent.CONSUMER);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setMessageListener((ChannelAwareMessageListener) handler::onMessage);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(false);
        container.setDefaultRequeueRejected(false);
        container.setConcurrentConsumers(1);
        container.setPrefetchCount(properties.prefetch());
        container.setRecoveryInterval(properties.recoveryDelayMs());
        container.setErrorHandler(error -> LoggerFactory.getLogger(CreatedDeliveryHandler.class)
                .error("Consumidor de criacao interrompeu entrega. Tipo={}", error.getClass().getSimpleName()));
        return container;
    }
}
