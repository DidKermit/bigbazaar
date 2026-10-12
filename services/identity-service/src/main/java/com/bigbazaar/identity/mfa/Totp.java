package com.bigbazaar.identity.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP with the parameters every authenticator app supports: HMAC-SHA1, 6 digits, 30 s.
 */
public final class Totp {

    public static final int PERIOD_SECONDS = 30;
    public static final int DIGITS = 6;
    /** Accept one step either side to absorb clock drift between phone and server. */
    static final int ALLOWED_DRIFT_STEPS = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long stepAt(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), PERIOD_SECONDS);
    }

    public static String codeAt(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the matching time step, or empty. Steps at or before {@code lastUsedStep} are refused so
     * a code can't be replayed within its validity window.
     */
    static OptionalLong verify(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = stepAt(now);
        for (long step = current - ALLOWED_DRIFT_STEPS; step <= current + ALLOWED_DRIFT_STEPS; step++) {
            boolean matches = MessageDigest.isEqual(codeAt(secret, step).getBytes(), code.getBytes());
            if (matches && step > lastUsedStep) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    private Totp() {
    }
}
