package com.bigbazaar.identity.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mfa_recovery_codes")
class RecoveryCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected RecoveryCode() {
    }

    RecoveryCode(UUID userId, String codeHash, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = now;
    }

    void use(Instant now) {
        usedAt = now;
    }
}
