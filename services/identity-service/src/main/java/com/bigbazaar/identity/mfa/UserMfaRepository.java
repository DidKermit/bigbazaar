package com.bigbazaar.identity.mfa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {

    /** Row lock so two concurrent requests can't both accept the same TOTP step. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from UserMfa m where m.userId = :userId")
    Optional<UserMfa> lockByUserId(UUID userId);
}
