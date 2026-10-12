package com.bigbazaar.identity.seller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface SellerDocumentRepository extends JpaRepository<SellerDocument, UUID> {

    List<SellerDocument> findBySellerIdOrderByCreatedAtAsc(UUID sellerId);

    Optional<SellerDocument> findByIdAndSellerId(UUID id, UUID sellerId);

    List<SellerDocument> findByStatusAndCreatedAtBefore(SellerDocument.Status status, Instant cutoff, Limit limit);
}
