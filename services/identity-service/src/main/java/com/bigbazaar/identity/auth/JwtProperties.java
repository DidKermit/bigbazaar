package com.bigbazaar.identity.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Access tokens are signed with an RSA private key that only identity-service holds. Everyone else
 * verifies with the public key published at {@code /.well-known/jwks.json}.
 */
@ConfigurationProperties(prefix = "bigbazaar.jwt")
public record JwtProperties(
        String issuer,
        Resource privateKeyLocation,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
    public JwtProperties {
        if (privateKeyLocation == null || !privateKeyLocation.exists()) {
            throw new IllegalStateException("bigbazaar.jwt.private-key-location must point to a PKCS#8 RSA key "
                    + "(set JWT_PRIVATE_KEY_PATH; generate one with infra/keys/generate-dev-keys.sh)");
        }
        if (issuer == null) {
            issuer = "bigbazaar-identity";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(14);
        }
    }
}
