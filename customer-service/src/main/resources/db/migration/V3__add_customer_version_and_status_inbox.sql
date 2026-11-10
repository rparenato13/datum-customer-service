ALTER TABLE customers ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

CREATE TABLE customer_event_inbox (
    consumer_id VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    customer_id BIGINT NOT NULL CHECK (customer_id > 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    fingerprint VARCHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('PROCESSING', 'APPLIED', 'REJECTED')),
    reason_code VARCHAR(40),
    processed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (consumer_id, event_id),
    CHECK ((outcome = 'PROCESSING') = (processed_at IS NULL)),
    CHECK ((outcome = 'REJECTED') = (reason_code IS NOT NULL)),
    CHECK (reason_code IS NULL OR reason_code IN ('CUSTOMER_NOT_FOUND', 'VERSION_CONFLICT'))
);

CREATE INDEX ix_customer_event_inbox_customer ON customer_event_inbox (customer_id, processed_at);
