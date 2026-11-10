package br.com.datum.customer.infrastructure.outbox;

public interface EventPublisher {
    enum Result { CONFIRMED, RETURNED, NACK, CONFIRM_TIMEOUT, BROKER_UNAVAILABLE, INTERRUPTED }
    Result publish(OutboxEvent event);
}
