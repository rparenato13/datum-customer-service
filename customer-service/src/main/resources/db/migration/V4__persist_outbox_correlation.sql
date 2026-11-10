ALTER TABLE customer_outbox ADD COLUMN correlation_id UUID;
-- Eventos anteriores a T12 mantêm a correlação técnica pelo próprio event_id.
UPDATE customer_outbox SET correlation_id = event_id;
ALTER TABLE customer_outbox ALTER COLUMN correlation_id SET NOT NULL;
ALTER TABLE customer_outbox ALTER COLUMN correlation_id SET DEFAULT gen_random_uuid();

-- A coleta do backlog não precisa percorrer o histórico já publicado.
CREATE INDEX ix_customer_outbox_observation ON customer_outbox (state, occurred_at)
    WHERE state IN ('PENDING', 'BLOCKED');
