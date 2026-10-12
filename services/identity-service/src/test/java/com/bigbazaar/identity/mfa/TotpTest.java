package com.bigbazaar.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    // RFC 6238 Appendix B, SHA1 seed; the RFC lists 8-digit codes, we use the last 6.
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238TestVectors() {
        assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfDriftButNotTwo() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.stepAt(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step - 1), now, 0)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step + 1), now, 0)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step - 2), now, 0)).isEmpty();
    }

    @Test
    void refusesReplayOfAnAlreadyUsedStep() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.stepAt(now);
        String code = Totp.codeAt(RFC_SECRET, step);
        assertThat(Totp.verify(RFC_SECRET, code, now, step - 1)).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, code, now, step)).isEmpty();
    }

    @Test
    void rejectsMalformedCodes() {
        assertThat(Totp.verify(RFC_SECRET, "12345", Instant.now(), 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", Instant.now(), 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, null, Instant.now(), 0)).isEmpty();
    }

    @Test
    void base32MatchesRfc4648() {
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }
}
