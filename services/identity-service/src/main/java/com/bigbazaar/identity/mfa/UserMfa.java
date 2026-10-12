package com.bigbazaar.identity.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_mfa")
class UserMfa {

    enum Status { PENDING, ACTIVE }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "totp_secret_encrypted", nullable = false)
    private String totpSecretEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "last_used_step", nullable = false)
    private long lastUsedStep;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Version
    private long version;

    protected UserMfa() {
    }

    UserMfa(UUID userId, String totpSecretEncrypted, Instant now) {
        this.userId = userId;
        restart(totpSecretEncrypted, now);
    }

    void restart(String encryptedSecret, Instant now) {
        this.totpSecretEncrypted = encryptedSecret;
        this.status = Status.PENDING;
        this.lastUsedStep = 0;
        this.createdAt = now;
        this.activatedAt = null;
    }

    void activate(long step, Instant now) {
        status = Status.ACTIVE;
        activatedAt = now;
        lastUsedStep = step;
    }

    void markStepUsed(long step) {
        lastUsedStep = step;
    }

    boolean isActive() {
        return status == Status.ACTIVE;
    }

    UUID getUserId() {
        return userId;
    }

    String getTotpSecretEncrypted() {
        return totpSecretEncrypted;
    }

    long getLastUsedStep() {
        return lastUsedStep;
    }

    Instant getActivatedAt() {
        return activatedAt;
    }
}
