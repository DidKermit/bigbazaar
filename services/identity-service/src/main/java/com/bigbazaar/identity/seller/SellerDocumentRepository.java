package com.bigbazaar.identity.seller;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SellerDocumentRepository extends JpaRepository<SellerDocument, UUID> {

    List<SellerDocument> findBySellerIdOrderByUploadedAtAsc(UUID sellerId);
}
