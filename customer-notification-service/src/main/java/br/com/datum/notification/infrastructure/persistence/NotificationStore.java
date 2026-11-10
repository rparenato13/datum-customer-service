package br.com.datum.notification.infrastructure.persistence;

import br.com.datum.notification.application.CustomerCreatedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

public class NotificationStore {
    public record Entry(String fingerprint, String outcome) { }
    private final JdbcTemplate jdbc;

    public NotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean begin(CustomerCreatedEvent event) {
        // A chave unica serializa entregas concorrentes. O rollback libera a
        // proxima tentativa, sem deixar uma chave que esconda efeito ausente.
        return jdbc.update("""
                INSERT INTO notification_event_inbox
                    (consumer_id, event_id, customer_id, occurred_at, fingerprint, outcome)
                VALUES (?, ?, ?, ?, ?, 'PROCESSING')
                ON CONFLICT (consumer_id, event_id) DO NOTHING
                """, CustomerCreatedEvent.CONSUMER, event.eventId(), event.customerId(),
                Timestamp.from(event.occurredAt()), event.fingerprint()) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Entry existing(CustomerCreatedEvent event) {
        return jdbc.queryForObject("""
                SELECT fingerprint, outcome FROM notification_event_inbox WHERE consumer_id = ? AND event_id = ?
                """, (rs, index) -> new Entry(rs.getString("fingerprint"), rs.getString("outcome")),
                CustomerCreatedEvent.CONSUMER, event.eventId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void register(CustomerCreatedEvent event) {
        jdbc.update("""
                INSERT INTO notification_records (notification_id, consumer_id, event_id, customer_id, kind)
                VALUES (?, ?, ?, ?, 'CUSTOMER_CREATED_SIMULATION')
                """, UUID.randomUUID(), CustomerCreatedEvent.CONSUMER, event.eventId(), event.customerId());
        int updated = jdbc.update("""
                UPDATE notification_event_inbox SET outcome = 'NOTIFICATION_REGISTERED', processed_at = clock_timestamp()
                WHERE consumer_id = ? AND event_id = ? AND outcome = 'PROCESSING'
                """, CustomerCreatedEvent.CONSUMER, event.eventId());
        if (updated != 1) throw new IllegalStateException("Estado de processamento inconsistente.");
    }
}
