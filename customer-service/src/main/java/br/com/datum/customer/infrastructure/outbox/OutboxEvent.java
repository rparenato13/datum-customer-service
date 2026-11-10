package br.com.datum.customer.infrastructure.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID eventId, long customerId, String eventType, int schemaVersion,
                          Instant occurredAt, String payload, long attemptCount, UUID claimToken, UUID correlationId) {
    public OutboxEvent(UUID eventId, long customerId, String eventType, int schemaVersion,
                       Instant occurredAt, String payload, long attemptCount, UUID claimToken) {
        this(eventId, customerId, eventType, schemaVersion, occurredAt, payload, attemptCount, claimToken, eventId);
    }
}
