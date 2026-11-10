package br.com.datum.customer.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("!smoke")
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxConfiguration {
    @Bean
    CustomerCreatedEventCodec customerCreatedEventCodec(ObjectMapper mapper) {
        return new CustomerCreatedEventCodec(mapper);
    }

    @Bean
    OutboxStore outboxStore(JdbcTemplate jdbc, CustomerCreatedEventCodec codec, OutboxProperties properties) {
        return new OutboxStore(jdbc, codec, properties);
    }

    @Bean
    EventPublisher eventPublisher(RabbitTemplate template, OutboxProperties properties) {
        if (!(template.getConnectionFactory() instanceof CachingConnectionFactory factory)) {
            throw new IllegalStateException("Publicacao outbox exige connection factory com confirms correlacionados.");
        }
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        template.setMandatory(true);
        return new RabbitEventPublisher(template, properties);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxStore store, EventPublisher publisher, CustomerCreatedEventCodec codec, OutboxProperties properties) {
        return new OutboxRelay(store, publisher, codec, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "customer.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
    OutboxScheduler outboxScheduler(OutboxRelay relay) {
        return new OutboxScheduler(relay);
    }
}
