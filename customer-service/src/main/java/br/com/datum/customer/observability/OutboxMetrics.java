package br.com.datum.customer.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("observability & !smoke")
public class OutboxMetrics {
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(Double.NaN, Double.NaN, Double.NaN, 0, 0);
    private record Snapshot(double pending, double blocked, double age, double up, long refreshed) { }

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("datum.outbox.pending", this, metrics -> metrics.snapshot.pending()).register(registry);
        Gauge.builder("datum.outbox.blocked", this, metrics -> metrics.snapshot.blocked()).register(registry);
        Gauge.builder("datum.outbox.oldest.pending.age", this, metrics -> metrics.snapshot.age())
                .baseUnit("seconds").register(registry);
        Gauge.builder("datum.outbox.collection.up", this, metrics -> metrics.snapshot.up()).register(registry);
        Gauge.builder("datum.outbox.collection.age", this, metrics -> metrics.snapshot.refreshed() == 0
                ? Double.NaN : (System.nanoTime() - metrics.snapshot.refreshed()) / 1_000_000_000.0)
                .baseUnit("seconds").register(registry);
    }

    // Scrapes consultam somente o snapshot em memória, nunca o banco.
    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    public void refresh() {
        try {
            snapshot = jdbc.queryForObject("""
                    SELECT count(*) FILTER (WHERE state = 'PENDING') AS pending,
                           count(*) FILTER (WHERE state = 'BLOCKED') AS blocked,
                           greatest(0, extract(epoch FROM clock_timestamp() -
                               min(occurred_at) FILTER (WHERE state = 'PENDING'))) AS age
                    FROM customer_outbox WHERE state IN ('PENDING', 'BLOCKED')
                    """, (rs, row) -> new Snapshot(rs.getLong("pending"), rs.getLong("blocked"),
                    rs.getDouble("age"), 1, System.nanoTime()));
        } catch (RuntimeException error) {
            var previous = snapshot;
            snapshot = new Snapshot(previous.pending(), previous.blocked(), previous.age(), 0, previous.refreshed());
            LoggerFactory.getLogger(OutboxMetrics.class).atWarn().addKeyValue("operation", "outbox.metrics")
                    .addKeyValue("outcome", "error").addKeyValue("exceptionClass", error.getClass().getSimpleName())
                    .log("Snapshot de outbox indisponivel; consulte collection.up e collection.age.");
        }
    }
}
