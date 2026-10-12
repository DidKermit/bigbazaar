package com.bigbazaar.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read model for the query API. Rows are inserted by {@link AuditRecorder} with plain SQL. */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private Long id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "event_version")
    private int eventVersion;

    @Column(name = "source_service")
    private String sourceService;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    private String reason;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    protected AuditEvent() {
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public String getSourceService() {
        return sourceService;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getReason() {
        return reason;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getDetails() {
        return details;
    }
}
