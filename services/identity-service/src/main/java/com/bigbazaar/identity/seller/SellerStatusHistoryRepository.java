package com.bigbazaar.identity.seller;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SellerStatusHistoryRepository extends JpaRepository<SellerStatusHistory, Long> {

    List<SellerStatusHistory> findBySellerIdOrderByChangedAtAscIdAsc(UUID sellerId);
}
