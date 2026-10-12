package com.bigbazaar.identity.auth;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.auth.AuthDtos.LoginRequest;
import com.bigbazaar.identity.auth.AuthDtos.RegisterRequest;
import com.bigbazaar.identity.auth.AuthDtos.RegisteredUser;
import com.bigbazaar.identity.auth.AuthDtos.TokenResponse;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import com.bigbazaar.identity.common.IdentityEvents;
import com.bigbazaar.identity.outbox.OutboxWriter;
import com.bigbazaar.identity.user.User;
import com.bigbazaar.identity.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final OutboxWriter outbox;
    private final JwtProperties jwtProperties;
    private final LoginProtectionProperties loginProtection;
    private final Clock clock;
    // Compared against when the email is unknown so response time doesn't reveal which emails exist.
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       AccessTokenIssuer accessTokenIssuer, OutboxWriter outbox, JwtProperties jwtProperties,
                       LoginProtectionProperties loginProtection, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.outbox = outbox;
        this.jwtProperties = jwtProperties;
        this.loginProtection = loginProtection;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public RegisteredUser register(RegisterRequest request) {
        if (users.existsActiveByEmail(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists.");
        }
        User user = users.save(User.register(request.name().trim(), request.email(), request.phone(),
                passwordEncoder.encode(request.password())));
        var roles = user.getRoles().stream().map(Enum::name).sorted().toList();
        outbox.write(Topics.IDENTITY_EVENTS, "User", user.getId().toString(), IdentityEvents.USER_REGISTERED,
                new IdentityEvents.UserRegistered(user.getId(), user.getName(), user.getEmail(), roles,
                        clock.instant()));
        log.info("User registered userId={}", user.getId());
        return new RegisteredUser(user.getId(), user.getName(), user.getEmail(), roles);
    }

    // noRollbackFor: a wrong password must still persist the failed-attempt counter.
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(LoginRequest request) {
        Instant now = clock.instant();
        User user = users.findActiveByEmail(request.email()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        if (user.isLockedAt(now)) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                    "Too many failed attempts. Try again later.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin(now, loginProtection.maxFailedAttempts(), loginProtection.lockDuration());
            log.info("Failed login userId={}", user.getId());
            throw invalidCredentials();
        }
        requireActive(user);
        user.recordSuccessfulLogin();
        return issueTokens(user, UUID.randomUUID(), now).response();
    }

    /**
     * Rotates a refresh token. Presenting a token that was already rotated means it leaked or was
     * replayed, so the whole family is revoked and the user must log in again.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse refresh(String presentedToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.lockByTokenHash(TokenHasher.sha256(presentedToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        if (current.isRevoked()) {
            if ("ROTATED".equals(current.getRevokeReason())) {
                int revoked = refreshTokens.revokeFamily(current.getFamilyId(), now, "REUSE_DETECTED");
                log.warn("Refresh token reuse detected userId={} familyId={} revoked={}",
                        current.getUserId(), current.getFamilyId(), revoked);
            }
            throw invalidRefreshToken();
        }
        if (!current.isUsableAt(now)) {
            throw invalidRefreshToken();
        }
        User user = users.findById(current.getUserId()).orElseThrow(AuthService::invalidRefreshToken);
        if (!user.isActive()) {
            refreshTokens.revokeFamily(current.getFamilyId(), now, "ACCOUNT_NOT_ACTIVE");
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE, "This account is not active.");
        }
        Issued issued = issueTokens(user, current.getFamilyId(), now);
        current.rotateTo(issued.refreshToken().getId(), now);
        return issued.response();
    }

    /** Revokes the presented token's family. Idempotent: unknown or revoked tokens are ignored. */
    @Transactional
    public void logout(String presentedToken) {
        refreshTokens.findByTokenHash(TokenHasher.sha256(presentedToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant(), "LOGOUT"));
    }

    @Transactional
    public void revokeAllSessions(UUID userId, String reason) {
        refreshTokens.revokeAllForUser(userId, clock.instant(), reason);
    }

    private Issued issueTokens(User user, UUID familyId, Instant now) {
        String opaque = TokenHasher.newOpaqueToken();
        Instant refreshExpiresAt = now.plus(jwtProperties.refreshTokenTtl());
        RefreshToken saved = refreshTokens.save(
                new RefreshToken(user.getId(), familyId, TokenHasher.sha256(opaque), now, refreshExpiresAt));
        String accessToken = accessTokenIssuer.issue(user, now);
        TokenResponse response = new TokenResponse("Bearer", accessToken, now.plus(jwtProperties.accessTokenTtl()),
                opaque, refreshExpiresAt);
        return new Issued(response, saved);
    }

    private record Issued(TokenResponse response, RefreshToken refreshToken) {
    }

    private static void requireActive(User user) {
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE, "This account is not active.");
        }
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
    }

    private static BusinessException invalidRefreshToken() {
        return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token is invalid or expired.");
    }
}
