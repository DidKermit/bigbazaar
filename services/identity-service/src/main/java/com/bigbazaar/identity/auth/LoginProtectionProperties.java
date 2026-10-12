package com.bigbazaar.identity.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-account lockout. Per-IP rate limiting happens at the gateway (FR-AUTH-07). */
@ConfigurationProperties(prefix = "bigbazaar.login")
public record LoginProtectionProperties(int maxFailedAttempts, Duration lockDuration) {
    public LoginProtectionProperties {
        if (maxFailedAttempts <= 0) {
            maxFailedAttempts = 5;
        }
        if (lockDuration == null) {
            lockDuration = Duration.ofMinutes(15);
        }
    }
}
