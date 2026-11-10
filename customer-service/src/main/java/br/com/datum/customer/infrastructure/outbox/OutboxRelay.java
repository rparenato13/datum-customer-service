package br.com.datum.customer.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxStore store;
    private final EventPublisher publisher;
    private final CustomerCreatedEventCodec codec;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxStore store, EventPublisher publisher, CustomerCreatedEventCodec codec, OutboxProperties properties) {
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
            if (!codec.isValid(event)) {
                store.failed(event, "INVALID_EVENT", true);
                LOG.error("Evento outbox bloqueado. eventId={} code=INVALID_EVENT", event.eventId());
                continue;
            }
            var result = publisher.publish(event);
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
