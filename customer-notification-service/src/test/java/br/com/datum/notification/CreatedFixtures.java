package br.com.datum.notification;

import br.com.datum.notification.application.CustomerCreatedEvent;
import br.com.datum.notification.messaging.CreatedMessageMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CreatedFixtures {
    private CreatedFixtures() { }

    public static CustomerCreatedEvent event(long customerId) {
        return new CustomerCreatedEvent(UUID.randomUUID(), Instant.parse("2026-11-10T03:00:00.123Z"), customerId);
    }

    public static byte[] body(CustomerCreatedEvent event) {
        try {
            return new ObjectMapper().writeValueAsBytes(Map.of("eventId", event.eventId().toString(), "eventType", CustomerCreatedEvent.TYPE,
                    "schemaVersion", 1, "occurredAt", event.occurredAt().toString(), "customerId", event.customerId()));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public static Message received(CustomerCreatedEvent event, int attempt) {
        var properties = new MessageProperties();
        properties.setMessageId(event.eventId().toString());
        properties.setCorrelationId(event.eventId().toString());
        properties.setType(CustomerCreatedEvent.TYPE);
        properties.setContentType("application/json");
        properties.setContentEncoding("UTF-8");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setDeliveryTag(42);
        if (attempt == 0) {
            properties.setReceivedExchange(CreatedMessageMetadata.EVENTS);
            properties.setReceivedRoutingKey(CreatedMessageMetadata.KEY);
        } else {
            properties.setReceivedExchange(CreatedMessageMetadata.REDELIVERY);
            properties.setReceivedRoutingKey(CustomerCreatedEvent.CONSUMER);
            properties.setHeader("x-retry-attempt", attempt);
            properties.setHeader("x-death", List.of(Map.of("reason", "expired")));
        }
        return new Message(body(event), properties);
    }
}
