package com.bigbazaar.audit;

import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent write: the processed-event marker and the audit row commit together, so a redelivered
 * event is either fully recorded already (and skipped) or not at all (and recorded now).
 */
@Component
class AuditRecorder {

    static final String CONSUMER = "audit-service";
    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final JdbcTemplate jdbc;

    AuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when the event was already recorded */
    @Transactional
    public boolean record(AuditEntry entry) {
        int fresh = jdbc.update("""
                INSERT INTO processed_events (consumer, event_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING""", CONSUMER, entry.eventId());
        if (fresh == 0) {
            log.info("Duplicate event ignored eventId={} eventType={}", entry.eventId(), entry.eventType());
            return false;
        }
        jdbc.update("""
                INSERT INTO audit_events (event_id, event_type, event_version, source_service, actor_id,
                                          entity_type, entity_id, reason, correlation_id, occurred_at, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
                entry.eventId(), entry.eventType(), entry.eventVersion(), entry.sourceService(), entry.actorId(),
                entry.entityType(), entry.entityId(), entry.reason(), entry.correlationId(),
                Timestamp.from(entry.occurredAt()), entry.details().toString());
        return true;
    }
}
