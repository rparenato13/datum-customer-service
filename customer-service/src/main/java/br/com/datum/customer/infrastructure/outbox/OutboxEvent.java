package br.com.datum.customer.infrastructure.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID eventId, long customerId, String eventType, int schemaVersion,
                          Instant occurredAt, String payload, long attemptCount, UUID claimToken) {
}
