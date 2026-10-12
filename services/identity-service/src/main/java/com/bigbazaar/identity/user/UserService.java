package com.bigbazaar.identity.user;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.auth.RefreshTokenRepository;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import com.bigbazaar.identity.common.IdentityEvents;
import com.bigbazaar.identity.outbox.OutboxWriter;
import com.bigbazaar.identity.user.UserDtos.AddressRequest;
import com.bigbazaar.identity.user.UserDtos.AddressResponse;
import com.bigbazaar.identity.user.UserDtos.UserProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final AddressRepository addresses;
    private final RefreshTokenRepository refreshTokens;
    private final OutboxWriter outbox;

    public UserService(UserRepository users, AddressRepository addresses, RefreshTokenRepository refreshTokens,
                       OutboxWriter outbox) {
        this.users = users;
        this.addresses = addresses;
        this.refreshTokens = refreshTokens;
        this.outbox = outbox;
    }

    /**
     * Loads the caller and rejects suspended or locked accounts (FR-AUTH-06). Access tokens are
     * short-lived, so this check closes the gap between a suspension and token expiry.
     */
    @Transactional(readOnly = true)
    public User requireActive(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found."));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE, "This account is not active.");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found."));
    }

    @Transactional(readOnly = true)
    public UserProfile profile(UUID userId) {
        return UserProfile.from(requireActive(userId));
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> listAddresses(UUID userId) {
        requireActive(userId);
        return addresses.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId).stream()
                .map(AddressResponse::from).toList();
    }

    @Transactional
    public AddressResponse addAddress(UUID userId, AddressRequest request) {
        requireActive(userId);
        List<Address> existing = addresses.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId);
        boolean makeDefault = request.makeDefault() || existing.isEmpty();
        if (makeDefault) {
            existing.forEach(Address::unsetDefault);
        }
        Address address = addresses.save(new Address(userId, request.label(), request.recipient(), request.phone(),
                request.line1(), request.line2(), request.city(), request.state(), request.postalCode(),
                request.country(), makeDefault));
        return AddressResponse.from(address);
    }

    @Transactional
    public void deleteAddress(UUID userId, UUID addressId) {
        requireActive(userId);
        addresses.findByIdAndUserIdAndDeletedAtIsNull(addressId, userId)
                .ifPresent(address -> address.softDelete(userId, "Removed by user"));
    }

    @Transactional
    public UserProfile suspend(UUID userId, String reason, UUID actorId) {
        if (userId.equals(actorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Admins cannot suspend themselves.");
        }
        return changeStatus(userId, UserStatus.SUSPENDED, reason, actorId, IdentityEvents.USER_SUSPENDED);
    }

    @Transactional
    public UserProfile reactivate(UUID userId, String reason, UUID actorId) {
        return changeStatus(userId, UserStatus.ACTIVE, reason, actorId, IdentityEvents.USER_REACTIVATED);
    }

    private UserProfile changeStatus(UUID userId, UserStatus target, String reason, UUID actorId, String eventType) {
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found."));
        UserStatus previous = user.getStatus();
        if (previous == target) {
            return UserProfile.from(user);
        }
        user.changeStatus(target);
        if (target != UserStatus.ACTIVE) {
            refreshTokens.revokeAllForUser(userId, Instant.now(), "ACCOUNT_" + target);
        }
        outbox.write(Topics.IDENTITY_EVENTS, "User", userId.toString(), eventType,
                new IdentityEvents.UserStatusChanged(userId, previous.name(), target.name(), reason, actorId));
        log.info("User status changed userId={} from={} to={} actorId={}", userId, previous, target, actorId);
        return UserProfile.from(user);
    }
}
