package com.bigbazaar.identity.mfa;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bigbazaar.mfa")
public record MfaProperties(
        /* Base64 of 32 random bytes; encrypts TOTP secrets at rest. */
        String encryptionKey,
        String issuerName,
        Duration challengeTtl,
        int maxChallengeAttempts,
        int recoveryCodeCount
) {
    public MfaProperties {
        byte[] key;
        try {
            key = encryptionKey == null ? new byte[0] : Base64.getDecoder().decode(encryptionKey);
        } catch (IllegalArgumentException e) {
            key = new byte[0];
        }
        if (key.length != 32) {
            throw new IllegalStateException("bigbazaar.mfa.encryption-key must be base64 of 32 bytes "
                    + "(set MFA_ENCRYPTION_KEY; generate with: openssl rand -base64 32)");
        }
        if (issuerName == null) {
            issuerName = "BigBazaar";
        }
        if (challengeTtl == null) {
            challengeTtl = Duration.ofMinutes(5);
        }
        if (maxChallengeAttempts <= 0) {
            maxChallengeAttempts = 5;
        }
        if (recoveryCodeCount <= 0) {
            recoveryCodeCount = 10;
        }
    }

    byte[] encryptionKeyBytes() {
        return Base64.getDecoder().decode(encryptionKey);
    }
}
