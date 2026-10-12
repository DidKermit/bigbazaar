-- Authenticator-app MFA (TOTP, RFC 6238) with recovery codes and a login challenge step.

CREATE TABLE user_mfa (
    user_id                UUID PRIMARY KEY REFERENCES users (id),
    totp_secret_encrypted  VARCHAR(200) NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    last_used_step         BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL,
    activated_at           TIMESTAMPTZ,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_user_mfa_status CHECK (status IN ('PENDING', 'ACTIVE'))
);

-- One-time codes for a lost phone. Stored as SHA-256 of an 80-bit random value.
CREATE TABLE mfa_recovery_codes (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    code_hash  VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    CONSTRAINT uk_mfa_recovery_codes_hash UNIQUE (code_hash)
);

CREATE INDEX idx_mfa_recovery_codes_user_unused ON mfa_recovery_codes (user_id) WHERE used_at IS NULL;

-- Issued after a correct password when MFA is on; exchanged for tokens with a valid second factor.
CREATE TABLE mfa_challenges (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(64) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT uk_mfa_challenges_hash UNIQUE (token_hash)
);

-- Refreshed access tokens keep the strength of the original login.
ALTER TABLE refresh_tokens ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT FALSE;
