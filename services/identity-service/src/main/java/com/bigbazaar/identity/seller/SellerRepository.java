package com.bigbazaar.identity.seller;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SellerRepository extends JpaRepository<Seller, UUID> {

    Optional<Seller> findByUserId(UUID userId);

    Page<Seller> findByStatus(SellerStatus status, Pageable pageable);
}
