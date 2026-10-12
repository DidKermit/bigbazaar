package com.bigbazaar.identity.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks a batch of pending rows. SKIP LOCKED lets several replicas relay concurrently
     * without double-publishing the same row.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(int batchSize);

    List<OutboxEvent> findByEventTypeOrderByCreatedAtAsc(String eventType);
}
