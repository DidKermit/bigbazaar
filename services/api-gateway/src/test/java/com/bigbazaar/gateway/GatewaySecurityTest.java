package com.bigbazaar.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bigbazaar.jwt.secret=" + GatewaySecurityTest.SECRET,
        // Nothing listens here: an authenticated request should get past security and fail upstream.
        "IDENTITY_SERVICE_URL=http://localhost:1",
        "spring.data.redis.port=1"
})
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    static final String SECRET = "gateway-test-secret-that-is-longer-than-32-bytes";

    @Autowired WebTestClient client;

    @Test
    void missingTokenIsRejectedAtTheEdgeWithCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        client.get().uri("/api/v1/users/me")
                .header("X-Correlation-Id", correlationId)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Correlation-Id", correlationId)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.correlationId").isEqualTo(correlationId);
    }

    @Test
    void tokenWithWrongSignatureIsRejected() {
        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token("some-other-secret-that-is-also-32-bytes-long", "bigbazaar-identity"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token(SECRET, "someone-else"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokenPassesTheEdgeAndIsRouted() {
        int status = client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token(SECRET, "bigbazaar-identity"))
                .exchange()
                .returnResult(String.class)
                .getStatus().value();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void unknownPathsAreDenied() {
        client.get().uri("/internal/anything").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }

    @Test
    void invalidCorrelationIdIsReplaced() {
        assertThat(CorrelationIdFilter.sanitize("<script>")).isNotEqualTo("<script>");
        String valid = UUID.randomUUID().toString();
        assertThat(CorrelationIdFilter.sanitize(valid)).isEqualTo(valid);
    }

    private static String token(String secret, String issuer) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("CUSTOMER"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
