package br.com.datum.customer.infrastructure.persistence;

import br.com.datum.customer.application.status.StatusChangeEvent;
import br.com.datum.customer.application.status.StatusDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

public class StatusInbox {
    public record Entry(String fingerprint, String outcome, String reason) { }
    private final JdbcTemplate jdbc;

    public StatusInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean begin(StatusChangeEvent event) {
        // A chave unica serializa entregas concorrentes do mesmo evento. Se a
        // primeira transacao falhar, a proxima podera inserir e processar.
        return jdbc.update("""
                INSERT INTO customer_event_inbox
                    (consumer_id, event_id, customer_id, occurred_at, fingerprint, outcome)
                VALUES (?, ?, ?, ?, ?, 'PROCESSING')
                ON CONFLICT (consumer_id, event_id) DO NOTHING
                """, StatusChangeEvent.CONSUMER, event.eventId(), event.customerId(),
                Timestamp.from(event.occurredAt()), event.fingerprint()) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Entry existing(StatusChangeEvent event) {
        return jdbc.queryForObject("""
                SELECT fingerprint, outcome, reason_code FROM customer_event_inbox
                WHERE consumer_id = ? AND event_id = ?
                """, (rs, index) -> new Entry(rs.getString("fingerprint"), rs.getString("outcome"), rs.getString("reason_code")),
                StatusChangeEvent.CONSUMER, event.eventId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(StatusChangeEvent event, StatusDecision decision) {
        int updated = jdbc.update("""
                UPDATE customer_event_inbox SET outcome = ?, reason_code = ?, processed_at = clock_timestamp()
                WHERE consumer_id = ? AND event_id = ? AND outcome = 'PROCESSING'
                """, decision == StatusDecision.APPLIED ? "APPLIED" : "REJECTED",
                decision == StatusDecision.APPLIED ? null : decision.name(), StatusChangeEvent.CONSUMER, event.eventId());
        if (updated != 1) {
            throw new IllegalStateException("Estado de processamento inconsistente.");
        }
    }
}
