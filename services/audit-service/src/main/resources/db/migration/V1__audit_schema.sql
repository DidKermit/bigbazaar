-- platform_db audit store (SRS 8.4 "platform/audit", FR-AUD-01/02). High-volume and append-only, so
-- BIGINT ids (SRS 8.2). One row per domain event; the event id is unique so replays can't duplicate.

CREATE TABLE audit_events (
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    event_version  INT          NOT NULL,
    source_service VARCHAR(60)  NOT NULL,
    actor_id       UUID,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      VARCHAR(64)  NOT NULL,
    reason         VARCHAR(1000),
    correlation_id UUID,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    details        JSONB        NOT NULL,
    CONSTRAINT uk_audit_events_event_id UNIQUE (event_id)
);

CREATE INDEX idx_audit_entity ON audit_events (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_actor ON audit_events (actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_occurred ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_correlation ON audit_events (correlation_id) WHERE correlation_id IS NOT NULL;

CREATE FUNCTION reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

-- Idempotent consumer bookkeeping (SRS 9.4): which events each consumer has already handled.
CREATE TABLE processed_events (
    consumer     VARCHAR(80) NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
