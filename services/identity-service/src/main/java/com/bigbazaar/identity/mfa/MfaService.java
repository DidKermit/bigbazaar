package com.bigbazaar.identity.mfa;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.auth.RefreshTokenRepository;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import com.bigbazaar.identity.common.IdentityEvents;
import com.bigbazaar.identity.common.TokenHasher;
import com.bigbazaar.identity.mfa.MfaDtos.MfaStatusResponse;
import com.bigbazaar.identity.mfa.MfaDtos.RecoveryCodesResponse;
import com.bigbazaar.identity.mfa.MfaDtos.TotpSetupResponse;
import com.bigbazaar.identity.outbox.OutboxWriter;
import com.bigbazaar.identity.user.User;
import com.bigbazaar.identity.user.UserService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticator-app enrollment, the login second step, recovery codes and admin reset. */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);
    private static final String METHOD = "TOTP";
    private static final char[] RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMfaRepository mfaRepository;
    private final RecoveryCodeRepository recoveryCodes;
    private final MfaChallengeRepository challenges;
    private final RefreshTokenRepository refreshTokens;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final SecretCipher cipher;
    private final OutboxWriter outbox;
    private final MfaProperties properties;
    private final Clock clock;

    public MfaService(UserMfaRepository mfaRepository, RecoveryCodeRepository recoveryCodes,
                      MfaChallengeRepository challenges, RefreshTokenRepository refreshTokens,
                      UserService userService, PasswordEncoder passwordEncoder, SecretCipher cipher,
                      OutboxWriter outbox, MfaProperties properties, Clock clock) {
        this.mfaRepository = mfaRepository;
        this.recoveryCodes = recoveryCodes;
        this.challenges = challenges;
        this.refreshTokens = refreshTokens;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return mfaRepository.findById(userId).map(UserMfa::isActive).orElse(false);
    }

    @Transactional(readOnly = true)
    public MfaStatusResponse status(UUID userId) {
        userService.requireActive(userId);
        return mfaRepository.findById(userId)
                .filter(UserMfa::isActive)
                .map(m -> new MfaStatusResponse(true, METHOD, m.getActivatedAt(), recoveryCodes.countUnused(userId)))
                .orElse(new MfaStatusResponse(false, null, null, 0));
    }

    /** Step 1 of enrollment. Needs the password so a stolen access token can't enroll an attacker's phone. */
    @Transactional
    public TotpSetupResponse startTotpSetup(UUID userId, String password) {
        User user = userService.requireActive(userId);
        requirePassword(user, password);
        Instant now = clock.instant();
        UserMfa existing = mfaRepository.findById(userId).orElse(null);
        if (existing != null && existing.isActive()) {
            throw new BusinessException(ErrorCode.MFA_ALREADY_ENABLED, "MFA is already enabled.");
        }
        byte[] secret = Totp.newSecret();
        String encrypted = cipher.encrypt(secret, userId.toString());
        if (existing == null) {
            mfaRepository.save(new UserMfa(userId, encrypted, now));
        } else {
            existing.restart(encrypted, now);
        }
        String base32 = Base32.encode(secret);
        String label = enc(properties.issuerName()) + ":" + enc(user.getEmail());
        String uri = "otpauth://totp/" + label + "?secret=" + base32 + "&issuer=" + enc(properties.issuerName())
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD_SECONDS;
        return new TotpSetupResponse(base32, uri, Totp.DIGITS, Totp.PERIOD_SECONDS);
    }

    /** Step 2: proves the app is set up correctly, turns MFA on and hands out recovery codes once. */
    @Transactional
    public RecoveryCodesResponse activateTotp(UUID userId, String code) {
        userService.requireActive(userId);
        UserMfa mfa = mfaRepository.lockByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MFA_SETUP_NOT_STARTED,
                        "Start authenticator setup first."));
        if (mfa.isActive()) {
            throw new BusinessException(ErrorCode.MFA_ALREADY_ENABLED, "MFA is already enabled.");
        }
        Instant now = clock.instant();
        long step = verifyTotp(mfa, code, now)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_MFA_CODE, "The code is not valid."));
        mfa.activate(step, now);
        List<String> codes = replaceRecoveryCodes(userId, now);
        // Sessions opened before MFA existed shouldn't outlive the change.
        refreshTokens.revokeAllForUser(userId, now, "MFA_ENABLED");
        outbox.write(Topics.IDENTITY_EVENTS, "User", userId.toString(), IdentityEvents.MFA_ENABLED,
                new IdentityEvents.MfaChanged(userId, METHOD, "Enabled by user", userId));
        log.info("MFA enabled userId={}", userId);
        return new RecoveryCodesResponse(codes,
                "Store these codes somewhere safe. Each works once if you lose your phone. "
                        + "Log in again to continue with MFA.");
    }

    @Transactional
    public RecoveryCodesResponse regenerateRecoveryCodes(UUID userId, String code) {
        userService.requireActive(userId);
        UserMfa mfa = lockActiveMfa(userId);
        Instant now = clock.instant();
        long step = verifyTotp(mfa, code, now)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_MFA_CODE, "The code is not valid."));
        mfa.markStepUsed(step);
        return new RecoveryCodesResponse(replaceRecoveryCodes(userId, now),
                "Your previous recovery codes no longer work.");
    }

    @Transactional
    public void disable(UUID userId, String password, String code) {
        User user = userService.requireActive(userId);
        requirePassword(user, password);
        UserMfa mfa = lockActiveMfa(userId);
        if (verifyTotp(mfa, code, clock.instant()).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_MFA_CODE, "The code is not valid.");
        }
        remove(userId, "Disabled by user", userId, IdentityEvents.MFA_DISABLED);
    }

    /** For a user who lost both phone and recovery codes. They re-enroll on next login. */
    @Transactional
    public void adminReset(UUID userId, String reason, UUID adminId) {
        if (userId.equals(adminId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Admins cannot reset their own MFA.");
        }
        userService.get(userId);
        if (mfaRepository.findById(userId).isEmpty()) {
            throw new BusinessException(ErrorCode.MFA_NOT_ENABLED, "This user has no MFA to reset.");
        }
        remove(userId, reason, adminId, IdentityEvents.MFA_RESET);
    }

    /** Issued after a correct password. Returns the opaque challenge token to send back with the code. */
    @Transactional
    public String createChallenge(UUID userId) {
        String token = TokenHasher.newOpaqueToken();
        Instant now = clock.instant();
        challenges.save(new MfaChallenge(userId, TokenHasher.sha256(token), now, now.plus(properties.challengeTtl())));
        return token;
    }

    public Instant challengeExpiry(Instant issuedAt) {
        return issuedAt.plus(properties.challengeTtl());
    }

    /**
     * Completes login. Exactly one of {@code code} or {@code recoveryCode} is used. Wrong codes count
     * against the challenge, which dies after the configured number of attempts.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public User completeChallenge(String challengeToken, String code, String recoveryCode) {
        Instant now = clock.instant();
        MfaChallenge challenge = challenges.lockByTokenHash(TokenHasher.sha256(challengeToken))
                .filter(c -> c.isUsableAt(now, properties.maxChallengeAttempts()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_MFA_CHALLENGE,
                        "The login attempt expired. Enter your email and password again."));
        UUID userId = challenge.getUserId();
        User user = userService.requireActive(userId);
        UserMfa mfa = lockActiveMfa(userId);

        boolean ok;
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            ok = recoveryCodes.lockUnused(userId, hashRecoveryCode(recoveryCode))
                    .map(rc -> {
                        rc.use(now);
                        return true;
                    })
                    .orElse(false);
            if (ok) {
                log.info("Recovery code used for login userId={}", userId);
            }
        } else {
            OptionalLong step = verifyTotp(mfa, code, now);
            step.ifPresent(mfa::markStepUsed);
            ok = step.isPresent();
        }
        if (!ok) {
            challenge.recordFailedAttempt();
            log.info("Failed MFA attempt userId={}", userId);
            throw new BusinessException(ErrorCode.INVALID_MFA_CODE, "The code is not valid.");
        }
        challenge.consume(now);
        return user;
    }

    private void remove(UUID userId, String reason, UUID actorId, String eventType) {
        Instant now = clock.instant();
        mfaRepository.deleteById(userId);
        recoveryCodes.deleteAllForUser(userId);
        refreshTokens.revokeAllForUser(userId, now, eventType.toUpperCase());
        outbox.write(Topics.IDENTITY_EVENTS, "User", userId.toString(), eventType,
                new IdentityEvents.MfaChanged(userId, METHOD, reason, actorId));
        log.info("MFA removed userId={} event={} actorId={}", userId, eventType, actorId);
    }

    private OptionalLong verifyTotp(UserMfa mfa, String code, Instant now) {
        byte[] secret = cipher.decrypt(mfa.getTotpSecretEncrypted(), mfa.getUserId().toString());
        return Totp.verify(secret, code, now, mfa.getLastUsedStep());
    }

    private UserMfa lockActiveMfa(UUID userId) {
        return mfaRepository.lockByUserId(userId).filter(UserMfa::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MFA_NOT_ENABLED, "MFA is not enabled."));
    }

    private void requirePassword(User user, String password) {
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Password is incorrect.");
        }
    }

    private List<String> replaceRecoveryCodes(UUID userId, Instant now) {
        recoveryCodes.deleteAllForUser(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < properties.recoveryCodeCount(); i++) {
            String code = newRecoveryCode();
            codes.add(code);
            recoveryCodes.save(new RecoveryCode(userId, hashRecoveryCode(code), now));
        }
        return codes;
    }

    /** 16 characters from a 32-symbol alphabet = 80 bits, shown as XXXX-XXXX-XXXX-XXXX. */
    private static String newRecoveryCode() {
        StringBuilder sb = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(RECOVERY_ALPHABET[RANDOM.nextInt(RECOVERY_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String hashRecoveryCode(String code) {
        return TokenHasher.sha256(code.replace("-", "").replace(" ", "").toUpperCase());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
