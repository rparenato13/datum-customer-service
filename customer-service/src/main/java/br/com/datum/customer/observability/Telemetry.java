package br.com.datum.customer.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class Telemetry {
    private final MeterRegistry registry;
    private final long slowQueryMs;

    public Telemetry(MeterRegistry registry, @Value("${observability.slow-query-threshold-ms:250}") long slowQueryMs) {
        this.registry = registry;
        this.slowQueryMs = slowQueryMs;
    }

    public static Telemetry standalone() { return new Telemetry(new SimpleMeterRegistry(), 250); }
    public MeterRegistry registry() { return registry; }
    public long slowQueryMs() { return slowQueryMs; }

    public void duration(String name, long nanos, String... tags) {
        Timer.builder(name).tags(tags).publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    public LoggingEventBuilder event(Logger logger, Level level, String operation, String outcome, long nanos) {
        return logger.atLevel(level).addKeyValue("operation", operation).addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", Math.max(0, nanos) / 1_000_000.0)
                .addKeyValue("dbDurationMs", CorrelationContext.databaseMs()).addKeyValue("dbQueryCount", CorrelationContext.queries());
    }
}
