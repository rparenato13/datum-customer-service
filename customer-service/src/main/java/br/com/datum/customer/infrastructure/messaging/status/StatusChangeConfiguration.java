package br.com.datum.customer.infrastructure.messaging.status;

import br.com.datum.customer.application.status.StatusChangeService;
import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.infrastructure.persistence.CustomerStatusWriter;
import br.com.datum.customer.infrastructure.persistence.StatusInbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
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
@EnableConfigurationProperties(StatusConsumerProperties.class)
public class StatusChangeConfiguration {
    @Bean
    StatusChangeCodec statusChangeCodec(ObjectMapper mapper) { return new StatusChangeCodec(mapper); }

    @Bean
    StatusMessageMetadata statusMessageMetadata() { return new StatusMessageMetadata(); }

    @Bean
    StatusInbox statusInbox(JdbcTemplate jdbc) { return new StatusInbox(jdbc); }

    @Bean
    CustomerStatusWriter customerStatusWriter(JdbcTemplate jdbc) { return new CustomerStatusWriter(jdbc); }

    @Bean
    StatusChangeService statusChangeService(StatusInbox inbox, CustomerStatusWriter customers) {
        return new StatusChangeService(inbox, customers);
    }

    @Bean
    StatusRetrySender statusRetrySender(RabbitTemplate template, StatusConsumerProperties properties) {
        template.setMandatory(true);
        return new ConfirmedStatusRetrySender(template, properties);
    }

    @Bean
    StatusChannelRecovery statusChannelRecovery(StatusConsumerProperties properties) {
        return channel -> {
            try {
                Thread.sleep(properties.recoveryDelayMs());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (channel.isOpen()) {
                channel.abort();
            }
        };
    }

    @Bean
    StatusDeliveryHandler statusDeliveryHandler(StatusChangeCodec codec, StatusMessageMetadata metadata,
            StatusChangeService service, StatusRetrySender retry, StatusChannelRecovery recovery, br.com.datum.customer.observability.Telemetry telemetry) {
        return new StatusDeliveryHandler(codec, metadata, service, retry, recovery, telemetry);
    }

    @Bean
    @ConditionalOnProperty(name = "customer.status-consumer.enabled", havingValue = "true", matchIfMissing = true)
    SimpleMessageListenerContainer statusChangeListener(ConnectionFactory factory, StatusDeliveryHandler handler,
                                                       StatusConsumerProperties properties) {
        var container = new SimpleMessageListenerContainer(factory);
        container.setQueueNames(StatusChangeEvent.CONSUMER);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setMessageListener((ChannelAwareMessageListener) handler::onMessage);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(false);
        container.setDefaultRequeueRejected(false);
        container.setConcurrentConsumers(1);
        container.setPrefetchCount(properties.prefetch());
        container.setRecoveryInterval(properties.recoveryDelayMs());
        container.setErrorHandler(error -> LoggerFactory.getLogger(StatusDeliveryHandler.class)
                .error("Consumidor status interrompeu entrega. Tipo={}", error.getClass().getSimpleName()));
        return container;
    }
}
