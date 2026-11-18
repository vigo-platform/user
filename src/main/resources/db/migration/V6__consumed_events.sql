-- Receipts for Kafka events consumed by the User service. Kept separate from the
-- outbox: the outbox publishes, this table deduplicates inbound deliveries.
CREATE TABLE consumed_events (
    consumer VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
