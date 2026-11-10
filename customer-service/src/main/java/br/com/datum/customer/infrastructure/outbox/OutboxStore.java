package br.com.datum.customer.infrastructure.outbox;

import br.com.datum.customer.application.events.CustomerCreatedEventRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class OutboxStore implements CustomerCreatedEventRecorder {
    private final JdbcTemplate jdbc;
    private final CustomerCreatedEventCodec codec;
    private final OutboxProperties properties;

    public OutboxStore(JdbcTemplate jdbc, CustomerCreatedEventCodec codec, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long customerId, Instant occurredAt) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer_outbox (event_id, customer_id, event_type, schema_version, occurred_at, payload)
                VALUES (?, ?, 'CUSTOMER_CREATED', 1, ?, ?)
                """, id, customerId, Timestamp.from(occurredAt), codec.encode(id, customerId, occurredAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxEvent> claim() {
        var token = UUID.randomUUID();
        return jdbc.query("""
                WITH candidate AS (
                    SELECT event_id FROM customer_outbox
                    WHERE state = 'PENDING' AND next_attempt_at <= clock_timestamp()
                      AND (locked_until IS NULL OR locked_until <= clock_timestamp())
                    ORDER BY next_attempt_at, occurred_at, event_id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE customer_outbox o SET attempt_count = attempt_count + 1,
                    last_attempt_at = clock_timestamp(), claim_token = ?,
                    locked_until = clock_timestamp() + (? * interval '1 millisecond')
                FROM candidate c WHERE o.event_id = c.event_id
                RETURNING o.event_id, o.customer_id, o.event_type, o.schema_version,
                    o.occurred_at, o.payload, o.attempt_count, o.claim_token
                """, (rs, row) -> new OutboxEvent(rs.getObject("event_id", UUID.class), rs.getLong("customer_id"),
                rs.getString("event_type"), rs.getInt("schema_version"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("payload"), rs.getLong("attempt_count"), rs.getObject("claim_token", UUID.class)),
                token, properties.leaseMs()).stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean published(OutboxEvent event) {
        return jdbc.update("""
                UPDATE customer_outbox SET state = 'PUBLISHED', published_at = clock_timestamp(),
                    last_error_code = NULL, claim_token = NULL, locked_until = NULL
                WHERE event_id = ? AND claim_token = ? AND state = 'PENDING'
                """, event.eventId(), event.claimToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(OutboxEvent event, String code, boolean blocked) {
        long delay = Math.min(30L, 1L << (int) Math.min(5L, Math.max(0L, event.attemptCount() - 1))) * 1000;
        return jdbc.update("""
                UPDATE customer_outbox SET state = ?, last_error_code = ?,
                    next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                    claim_token = NULL, locked_until = NULL
                WHERE event_id = ? AND claim_token = ? AND state = 'PENDING'
                """, blocked ? "BLOCKED" : "PENDING", code, delay, event.eventId(), event.claimToken()) == 1;
    }
}
