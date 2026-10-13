package com.bigbazaar.identity.seller;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerVerificationCheckRepository extends JpaRepository<SellerVerificationCheck, Long> {

    List<SellerVerificationCheck> findByVerificationIdOrderByIdAsc(UUID verificationId);
}
