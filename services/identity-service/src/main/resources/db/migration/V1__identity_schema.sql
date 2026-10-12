-- identity_db baseline (SRS 8.4). UUID ids, TIMESTAMPTZ, string enums, selective soft delete.

CREATE TABLE users (
    id                     UUID PRIMARY KEY,
    name                   VARCHAR(120) NOT NULL,
    email                  VARCHAR(254) NOT NULL,
    phone                  VARCHAR(20)  NOT NULL,
    password_hash          VARCHAR(100) NOT NULL,
    status                 VARCHAR(32)  NOT NULL,
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    deleted_at             TIMESTAMPTZ,
    deleted_by             UUID,
    delete_reason          VARCHAR(500),
    CONSTRAINT chk_users_status CHECK (status IN
        ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'SUSPENDED', 'DEACTIVATED'))
);

CREATE UNIQUE INDEX uk_users_email_active ON users (LOWER(email)) WHERE deleted_at IS NULL;

CREATE TABLE roles (
    name        VARCHAR(32) PRIMARY KEY,
    description VARCHAR(200) NOT NULL
);

INSERT INTO roles (name, description) VALUES
    ('CUSTOMER', 'Browse, buy, review, raise tickets'),
    ('SELLER',   'Manage owned products, inventory and seller orders'),
    ('SUPPORT',  'Handle assigned support tickets'),
    ('ADMIN',    'Platform policy, seller approval, audit');

CREATE TABLE user_roles (
    user_id    UUID        NOT NULL REFERENCES users (id),
    role       VARCHAR(32) NOT NULL REFERENCES roles (name),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE addresses (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id),
    label         VARCHAR(40),
    recipient     VARCHAR(120) NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    line1         VARCHAR(200) NOT NULL,
    line2         VARCHAR(200),
    city          VARCHAR(100) NOT NULL,
    state         VARCHAR(100) NOT NULL,
    postal_code   VARCHAR(12)  NOT NULL,
    country       VARCHAR(2)   NOT NULL DEFAULT 'IN',
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    deleted_at    TIMESTAMPTZ,
    deleted_by    UUID,
    delete_reason VARCHAR(500)
);

CREATE INDEX idx_addresses_user ON addresses (user_id) WHERE deleted_at IS NULL;

CREATE TABLE sellers (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users (id),
    business_name  VARCHAR(160) NOT NULL,
    business_email VARCHAR(254) NOT NULL,
    business_phone VARCHAR(20)  NOT NULL,
    tax_id         VARCHAR(20),
    pickup_address VARCHAR(500) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_sellers_user UNIQUE (user_id),
    CONSTRAINT chk_sellers_status CHECK (status IN
        ('DRAFT', 'PENDING_VERIFICATION', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED', 'CLOSED'))
);

CREATE INDEX idx_sellers_status_created ON sellers (status, created_at);

CREATE TABLE seller_documents (
    id            UUID PRIMARY KEY,
    seller_id     UUID         NOT NULL REFERENCES sellers (id),
    document_type VARCHAR(40)  NOT NULL,
    storage_key   VARCHAR(500) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    uploaded_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_seller_documents_seller ON seller_documents (seller_id);

-- Immutable history (FR-SELLER-03): rows can be inserted, never changed.
CREATE TABLE seller_status_history (
    id             BIGSERIAL PRIMARY KEY,
    seller_id      UUID         NOT NULL REFERENCES sellers (id),
    from_status    VARCHAR(32),
    to_status      VARCHAR(32)  NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    actor_id       UUID         NOT NULL,
    correlation_id UUID,
    changed_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_seller_status_history_seller ON seller_status_history (seller_id, changed_at);

CREATE FUNCTION reject_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_seller_status_history_immutable
    BEFORE UPDATE OR DELETE ON seller_status_history
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

-- Refresh tokens are stored hashed. A family groups one login's rotation chain so that reuse of
-- an already-rotated token can revoke the whole chain (FR-AUTH-04).
CREATE TABLE refresh_tokens (
    id             UUID PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users (id),
    family_id      UUID        NOT NULL,
    token_hash     VARCHAR(64) NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoke_reason  VARCHAR(40),
    replaced_by_id UUID,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

-- Transactional outbox (SRS 9.2).
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    envelope       JSONB        NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
