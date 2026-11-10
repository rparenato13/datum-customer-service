package br.com.datum.customer.application.status;

public enum StatusDecision {
    APPLIED, DUPLICATE, CUSTOMER_NOT_FOUND, VERSION_CONFLICT, EVENT_ID_CONFLICT;

    public boolean accepted() {
        return this == APPLIED || this == DUPLICATE;
    }
}
