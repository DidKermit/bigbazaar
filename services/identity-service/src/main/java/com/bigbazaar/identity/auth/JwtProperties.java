package com.bigbazaar.identity.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Access tokens are signed with an RSA private key that only identity-service holds. Everyone else
 * verifies with the public key published at {@code /.well-known/jwks.json}.
 *
 * <p>{@code previousPublicKeyLocations} keeps retired keys trusted (and published) after a rotation,
 * so tokens issued just before the switch stay valid until they expire. Drop them after one access
 * token lifetime. See docs/adr/0003.
 */
@ConfigurationProperties(prefix = "bigbazaar.jwt")
public record JwtProperties(
        String issuer,
        Resource privateKeyLocation,
        List<String> previousPublicKeyLocations,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
    public JwtProperties {
        if (privateKeyLocation == null || !privateKeyLocation.exists()) {
            throw new IllegalStateException("bigbazaar.jwt.private-key-location must point to a PKCS#8 RSA key "
                    + "(set JWT_PRIVATE_KEY_PATH; generate one with infra/keys/generate-dev-keys.sh)");
        }
        previousPublicKeyLocations = previousPublicKeyLocations == null ? List.of()
                : previousPublicKeyLocations.stream().filter(l -> l != null && !l.isBlank()).map(String::trim)
                        .toList();
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
