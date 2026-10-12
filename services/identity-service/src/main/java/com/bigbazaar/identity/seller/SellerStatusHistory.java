package com.bigbazaar.identity.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only; the database also rejects UPDATE and DELETE on this table. */
@Entity
@Immutable
@Table(name = "seller_status_history")
public class SellerStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private SellerStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private SellerStatus toStatus;

    @Column(nullable = false)
    private String reason;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected SellerStatusHistory() {
    }

    SellerStatusHistory(UUID sellerId, SellerStatus fromStatus, SellerStatus toStatus, String reason, UUID actorId,
                        UUID correlationId, Instant changedAt) {
        this.sellerId = sellerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorId = actorId;
        this.correlationId = correlationId;
        this.changedAt = changedAt;
    }

    public SellerStatus getFromStatus() {
        return fromStatus;
    }

    public SellerStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorId() {
        return actorId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
