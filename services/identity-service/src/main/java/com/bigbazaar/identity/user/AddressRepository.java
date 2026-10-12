package com.bigbazaar.identity.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);

    Optional<Address> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
}
