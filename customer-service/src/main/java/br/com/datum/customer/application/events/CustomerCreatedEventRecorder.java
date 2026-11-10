package br.com.datum.customer.application.events;

import java.time.Instant;

public interface CustomerCreatedEventRecorder {
    void record(long customerId, Instant occurredAt);
}
