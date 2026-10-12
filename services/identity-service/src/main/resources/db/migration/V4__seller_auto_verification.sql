-- Automatic seller verification (ADR 0007). Government-registry checks through a KYC provider replace
-- manual approval for clean applications; anything else goes to an admin with the results attached.

-- What the applicant gives for the checks. The account number and PAN date are personal data, so they
-- are kept AES-GCM encrypted (bound to the seller id); only the last four digits are readable.
ALTER TABLE sellers
    ADD COLUMN bank_ifsc          VARCHAR(11),
    ADD COLUMN bank_account_last4 VARCHAR(4),
    ADD COLUMN kyc_inputs_enc     TEXT;

-- One row per verification attempt (a re-application starts a new one).
CREATE TABLE seller_verifications (
    id                  UUID PRIMARY KEY,
    seller_id           UUID         NOT NULL REFERENCES sellers (id),
    status              VARCHAR(24)  NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    identity_session_id VARCHAR(100),
    attempts            INT          NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    locked_until        TIMESTAMPTZ,
    outcome_summary     VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_seller_verifications_status CHECK (status IN
        ('AWAITING_IDENTITY', 'READY', 'RUNNING', 'PASSED', 'NEEDS_REVIEW', 'SUPERSEDED'))
);

CREATE INDEX idx_seller_verifications_seller ON seller_verifications (seller_id, created_at DESC);
CREATE INDEX idx_seller_verifications_due ON seller_verifications (next_attempt_at)
    WHERE status IN ('READY', 'RUNNING');

-- The result of each individual check. Names returned by registries are kept so an admin can see
-- why a match failed; identifiers are not.
CREATE TABLE seller_verification_checks (
    id              BIGSERIAL PRIMARY KEY,
    verification_id UUID         NOT NULL REFERENCES seller_verifications (id),
    check_type      VARCHAR(20)  NOT NULL,
    outcome         VARCHAR(16)  NOT NULL,
    detail          VARCHAR(500) NOT NULL,
    registry_name   VARCHAR(200),
    match_score     NUMERIC(4,3),
    provider_ref    VARCHAR(100),
    checked_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_verification_check_type CHECK (check_type IN ('GSTIN', 'PAN', 'BANK_ACCOUNT', 'IDENTITY')),
    CONSTRAINT chk_verification_check_outcome CHECK (outcome IN ('PASS', 'FAIL', 'MISMATCH', 'MISSING', 'ERROR'))
);

CREATE INDEX idx_verification_checks_verification ON seller_verification_checks (verification_id);

CREATE TRIGGER trg_verification_checks_immutable
    BEFORE UPDATE OR DELETE ON seller_verification_checks
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
