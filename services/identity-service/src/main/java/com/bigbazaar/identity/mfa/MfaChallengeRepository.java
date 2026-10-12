package com.bigbazaar.identity.mfa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface MfaChallengeRepository extends JpaRepository<MfaChallenge, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MfaChallenge c where c.tokenHash = :tokenHash")
    Optional<MfaChallenge> lockByTokenHash(String tokenHash);
}
