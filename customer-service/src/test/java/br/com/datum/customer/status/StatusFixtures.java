package br.com.datum.customer.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.domain.CustomerStatus;
import br.com.datum.customer.infrastructure.messaging.status.StatusMessageMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StatusFixtures {
    private StatusFixtures() { }

    public static StatusChangeEvent event(long customerId, long version, CustomerStatus status) {
        return new StatusChangeEvent(UUID.randomUUID(), Instant.parse("2026-11-10T03:00:00.123Z"), customerId, status, version);
    }

    public static byte[] body(StatusChangeEvent event) {
        try {
            return new ObjectMapper().writeValueAsBytes(Map.of("eventId", event.eventId().toString(), "eventType", StatusChangeEvent.TYPE,
                    "schemaVersion", 1, "occurredAt", event.occurredAt().toString(), "customerId", event.customerId(),
                    "status", event.status().name(), "expectedVersion", event.expectedVersion()));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public static Message received(StatusChangeEvent event, int attempt) {
        var properties = new MessageProperties();
        properties.setMessageId(event.eventId().toString());
        properties.setCorrelationId(event.eventId().toString());
        properties.setType(StatusChangeEvent.TYPE);
        properties.setContentType("application/json");
        properties.setContentEncoding("UTF-8");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setDeliveryTag(42);
        if (attempt == 0) {
            properties.setReceivedExchange(StatusMessageMetadata.EVENTS);
            properties.setReceivedRoutingKey(StatusMessageMetadata.KEY);
        } else {
            properties.setReceivedExchange(StatusMessageMetadata.REDELIVERY);
            properties.setReceivedRoutingKey(StatusChangeEvent.CONSUMER);
            properties.setHeader("x-retry-attempt", attempt);
            properties.setHeader("x-death", List.of(Map.of("reason", "expired")));
        }
        return new Message(body(event), properties);
    }
}
