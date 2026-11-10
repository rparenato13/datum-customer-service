CREATE TABLE customer_outbox (
    event_id UUID PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    event_type VARCHAR(64) NOT NULL CHECK (event_type = 'CUSTOMER_CREATED'),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 16384),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'PUBLISHED', 'BLOCKED')),
    attempt_count BIGINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(40),
    claim_token UUID,
    locked_until TIMESTAMP WITH TIME ZONE,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_customer_outbox_created UNIQUE (customer_id, event_type),
    CONSTRAINT ck_customer_outbox_claim CHECK ((claim_token IS NULL) = (locked_until IS NULL)),
    CONSTRAINT ck_customer_outbox_published CHECK ((state = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE INDEX ix_customer_outbox_pending ON customer_outbox (next_attempt_at, occurred_at, event_id)
    WHERE state = 'PENDING';
