package com.bigbazaar.identity.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One automatic verification attempt for an application.
 *
 * <pre>
 * AWAITING_IDENTITY --seller finishes DigiLocker--> READY --worker--> RUNNING --> PASSED
 *                                                     ^                  |     \-> NEEDS_REVIEW
 *                                                     +--provider down---+
 * </pre>
 * A re-application marks the open attempt SUPERSEDED and starts a new one.
 */
@Entity
@Table(name = "seller_verifications")
public class SellerVerification {

    public enum Status { AWAITING_IDENTITY, READY, RUNNING, PASSED, NEEDS_REVIEW, SUPERSEDED }

    @Id
    private UUID id;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "identity_session_id")
    private String identitySessionId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "outcome_summary")
    private String outcomeSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected SellerVerification() {
    }

    SellerVerification(UUID sellerId, String provider, Instant now) {
        this.id = UUID.randomUUID();
        this.sellerId = sellerId;
        this.provider = provider;
        this.status = Status.AWAITING_IDENTITY;
        this.createdAt = now;
    }

    boolean isOpen() {
        return status == Status.AWAITING_IDENTITY || status == Status.READY || status == Status.RUNNING;
    }

    void identityStarted(String sessionId) {
        this.identitySessionId = sessionId;
    }

    void ready(Instant now) {
        status = Status.READY;
        nextAttemptAt = now;
    }

    void claim(Instant now, Instant lease) {
        status = Status.RUNNING;
        attempts++;
        lockedUntil = lease;
        nextAttemptAt = now;
    }

    void retryAt(Instant when) {
        status = Status.READY;
        lockedUntil = null;
        nextAttemptAt = when;
    }

    void complete(boolean passed, String summary, Instant now) {
        status = passed ? Status.PASSED : Status.NEEDS_REVIEW;
        outcomeSummary = summary;
        completedAt = now;
        lockedUntil = null;
        nextAttemptAt = null;
    }

    void supersede(Instant now) {
        status = Status.SUPERSEDED;
        completedAt = now;
        lockedUntil = null;
        nextAttemptAt = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public Status getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public String getIdentitySessionId() {
        return identitySessionId;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getOutcomeSummary() {
        return outcomeSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
