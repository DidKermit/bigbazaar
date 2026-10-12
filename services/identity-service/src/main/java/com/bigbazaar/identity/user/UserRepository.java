package com.bigbazaar.identity.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email) and u.deletedAt is null")
    Optional<User> findActiveByEmail(String email);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email) and u.deletedAt is null")
    boolean existsActiveByEmail(String email);
}
