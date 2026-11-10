CREATE TABLE notification_event_inbox (
    consumer_id VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    customer_id BIGINT NOT NULL CHECK (customer_id > 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    fingerprint VARCHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('PROCESSING', 'NOTIFICATION_REGISTERED')),
    processed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (consumer_id, event_id),
    CHECK ((outcome = 'PROCESSING') = (processed_at IS NULL))
);

CREATE TABLE notification_records (
    notification_id UUID PRIMARY KEY,
    consumer_id VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    customer_id BIGINT NOT NULL CHECK (customer_id > 0),
    kind VARCHAR(32) NOT NULL CHECK (kind = 'CUSTOMER_CREATED_SIMULATION'),
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (consumer_id, event_id),
    FOREIGN KEY (consumer_id, event_id) REFERENCES notification_event_inbox (consumer_id, event_id)
);

CREATE INDEX ix_notification_records_customer ON notification_records (customer_id, registered_at);
