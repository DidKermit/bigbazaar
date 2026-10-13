package com.bigbazaar.identity.seller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SellerVerificationRepository extends JpaRepository<SellerVerification, UUID> {

    Optional<SellerVerification> findFirstBySellerIdOrderByCreatedAtDesc(UUID sellerId);

    List<SellerVerification> findBySellerIdAndStatusIn(UUID sellerId, List<SellerVerification.Status> statuses);

    /** Due attempts, plus RUNNING ones whose worker died (lease expired). Replicas skip each other's rows. */
    @Query(value = """
            SELECT * FROM seller_verifications
            WHERE (status = 'READY' AND next_attempt_at <= :now)
               OR (status = 'RUNNING' AND locked_until < :now)
            ORDER BY next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<SellerVerification> lockDue(Instant now, int batchSize);
}
