package br.com.datum.customer.application.status;

import br.com.datum.customer.infrastructure.persistence.CustomerStatusWriter;
import br.com.datum.customer.infrastructure.persistence.StatusInbox;
import org.springframework.transaction.annotation.Transactional;

public class StatusChangeService {
    private final StatusInbox inbox;
    private final CustomerStatusWriter customers;

    public StatusChangeService(StatusInbox inbox, CustomerStatusWriter customers) {
        this.inbox = inbox;
        this.customers = customers;
    }

    @Transactional(timeout = 10)
    public StatusDecision process(StatusChangeEvent event) {
        if (!inbox.begin(event)) {
            var previous = inbox.existing(event);
            if (!event.fingerprint().equals(previous.fingerprint())) {
                return StatusDecision.EVENT_ID_CONFLICT;
            }
            if ("APPLIED".equals(previous.outcome())) {
                return StatusDecision.DUPLICATE;
            }
            if ("REJECTED".equals(previous.outcome())) {
                return StatusDecision.valueOf(previous.reason());
            }
            throw new IllegalStateException("Processamento anterior nao concluido.");
        }
        var version = customers.lockAvailableVersion(event.customerId());
        StatusDecision decision;
        if (version.isEmpty()) {
            decision = StatusDecision.CUSTOMER_NOT_FOUND;
        } else if (version.get() != event.expectedVersion() || !customers.change(event)) {
            decision = StatusDecision.VERSION_CONFLICT;
        } else {
            decision = StatusDecision.APPLIED;
        }
        inbox.finish(event, decision);
        return decision;
    }
}
