package com.bigbazaar.identity.mfa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RecoveryCode c where c.userId = :userId and c.codeHash = :codeHash and c.usedAt is null")
    Optional<RecoveryCode> lockUnused(UUID userId, String codeHash);

    @Query("select count(c) from RecoveryCode c where c.userId = :userId and c.usedAt is null")
    long countUnused(UUID userId);

    @Modifying
    @Query("delete from RecoveryCode c where c.userId = :userId")
    void deleteAllForUser(UUID userId);
}
