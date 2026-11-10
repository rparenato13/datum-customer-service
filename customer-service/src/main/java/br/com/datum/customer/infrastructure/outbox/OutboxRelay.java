package br.com.datum.customer.infrastructure.outbox;

import br.com.datum.customer.observability.CorrelationContext;
import br.com.datum.customer.observability.Telemetry;
import org.slf4j.event.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private final Telemetry telemetry;
    private final OutboxStore store;
    private final EventPublisher publisher;
    private final CustomerCreatedEventCodec codec;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxStore store, EventPublisher publisher, CustomerCreatedEventCodec codec, OutboxProperties properties) {
        this(store, publisher, codec, properties, Telemetry.standalone());
    }

    public OutboxRelay(OutboxStore store, EventPublisher publisher, CustomerCreatedEventCodec codec, OutboxProperties properties, Telemetry telemetry) {
        this.telemetry = telemetry;
        this.store = store;
        this.publisher = publisher;
        this.codec = codec;
        this.properties = properties;
    }

    public void runOnce() {
        for (int count = 0; count < properties.batchSize() && !Thread.currentThread().isInterrupted(); count++) {
            var candidate = store.claim();
            if (candidate.isEmpty()) {
                return;
            }
            var event = candidate.get();
            try (var scope = CorrelationContext.open(event.correlationId().toString())) {
                if (!codec.isValid(event)) {
                    store.failed(event, "INVALID_EVENT", true);
                    LOG.error("Evento outbox bloqueado. eventId={} code=INVALID_EVENT", event.eventId());
                    continue;
                }
                long start = System.nanoTime();
                var result = publisher.publish(event);
                long elapsed = System.nanoTime() - start;
                telemetry.duration("datum.outbox.publish.duration", elapsed, "outcome", result.name());
                telemetry.event(LOG, result == EventPublisher.Result.CONFIRMED ? Level.INFO : Level.WARN,
                                "outbox.publish", result.name(), elapsed)
                        .addKeyValue("eventId", event.eventId().toString()).addKeyValue("eventType", event.eventType())
                        .addKeyValue("attempt", event.attemptCount()).log("Tentativa de publicacao concluida.");
                if (result == EventPublisher.Result.CONFIRMED) {
                    store.published(event);
                } else {
                    store.failed(event, result.name(), false);
                    LOG.warn("Publicacao outbox pendente. eventId={} code={}", event.eventId(), result.name());
                    // Evitar multiplicar conexoes quando o broker esta indisponivel.
                    return;
                }
            }
        }
    }
}
