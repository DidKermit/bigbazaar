package com.bigbazaar.identity.kyc;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param provider       {@code mock} (development and tests) or {@code sandbox} (sandbox.co.in)
 * @param nameMatchScore minimum {@link NameMatcher} score for two names to count as the same person
 * @param maxAttempts    provider outages are retried this many times before an admin takes over
 */
@ConfigurationProperties(prefix = "bigbazaar.kyc")
public record KycProperties(String provider, double nameMatchScore, int maxAttempts, Duration retryDelay,
                            Duration pollInterval, Sandbox sandbox) {

    public KycProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.trim().toLowerCase();
        nameMatchScore = nameMatchScore <= 0 ? 0.8 : nameMatchScore;
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
        retryDelay = retryDelay == null ? Duration.ofMinutes(1) : retryDelay;
        pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
    }

    /**
     * @param baseUrl {@code https://test-api.sandbox.co.in} for test keys, {@code https://api.sandbox.co.in} live
     * @param reason  sent with each PAN check, as the API requires a stated purpose
     */
    public record Sandbox(URI baseUrl, String apiKey, String apiSecret, String reason, Duration timeout) {
        public Sandbox {
            baseUrl = baseUrl == null ? URI.create("https://test-api.sandbox.co.in") : baseUrl;
            reason = reason == null || reason.isBlank() ? "Seller onboarding verification for BigBazaar marketplace"
                    : reason;
            timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        }
    }
}
