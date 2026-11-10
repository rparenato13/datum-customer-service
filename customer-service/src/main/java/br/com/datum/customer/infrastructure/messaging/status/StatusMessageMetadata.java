package br.com.datum.customer.infrastructure.messaging.status;

import br.com.datum.customer.application.status.StatusChangeEvent;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.util.Set;
import br.com.datum.customer.observability.CorrelationContext;

public class StatusMessageMetadata {
    public static final String EVENTS = "datum.customer.events";
    public static final String KEY = "customer.status-change.v1";
    public static final String RETRY = "datum.customer.retry";
    public static final String REDELIVERY = "datum.customer.redelivery";
    private static final Set<String> HEADERS = Set.of(CorrelationContext.MESSAGE_HEADER, "x-retry-attempt", "x-death", "x-delivery-count", "x-acquired-count",
            "x-first-death-exchange", "x-first-death-queue", "x-first-death-reason",
            "x-last-death-exchange", "x-last-death-queue", "x-last-death-reason",
            "spring_listener_return_correlation", "spring_returned_message_correlation");

    public int attempt(MessageProperties properties, StatusChangeEvent event) {
        var id = event.eventId().toString();
        if (!id.equals(properties.getMessageId()) || !id.equals(properties.getCorrelationId())
                || !StatusChangeEvent.TYPE.equals(properties.getType())
                || !"application/json".equals(properties.getContentType())
                || !"UTF-8".equalsIgnoreCase(properties.getContentEncoding())
                || properties.getReceivedDeliveryMode() != MessageDeliveryMode.PERSISTENT
                || !HEADERS.containsAll(properties.getHeaders().keySet())
                || (properties.getHeaders().containsKey(CorrelationContext.MESSAGE_HEADER)
                    && !CorrelationContext.valid(properties.getHeaders().get(CorrelationContext.MESSAGE_HEADER)))) {
            throw new InvalidStatusEventException();
        }
        if (EVENTS.equals(properties.getReceivedExchange()) && KEY.equals(properties.getReceivedRoutingKey())) {
            if (properties.getHeaders().keySet().stream().anyMatch(key -> key.equals("x-retry-attempt")
                    || key.equals("x-death") || key.startsWith("x-first-death-") || key.startsWith("x-last-death-"))) {
                throw new InvalidStatusEventException();
            }
            return 0;
        }
        if (REDELIVERY.equals(properties.getReceivedExchange()) && StatusChangeEvent.CONSUMER.equals(properties.getReceivedRoutingKey())) {
            var retry = properties.getHeaders().get("x-retry-attempt");
            if ((retry instanceof Integer || retry instanceof Long || retry instanceof Short || retry instanceof Byte)
                    && ((Number) retry).longValue() >= 1 && ((Number) retry).longValue() <= 3
                    && properties.getHeaders().get("x-death") instanceof java.util.List<?>) {
                return ((Number) retry).intValue();
            }
        }
        throw new InvalidStatusEventException();
    }
}
