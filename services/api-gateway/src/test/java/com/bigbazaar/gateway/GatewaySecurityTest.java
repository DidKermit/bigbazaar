package com.bigbazaar.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Nothing listens here: an authenticated request should get past security and fail upstream.
        "IDENTITY_SERVICE_URL=http://127.0.0.1:1",
        "spring.data.redis.port=1"
})
@AutoConfigureWebTestClient
class GatewaySecurityTest {

    private static final RSAKey IDENTITY_KEY = newKey();
    private static final RSAKey ATTACKER_KEY = newKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired WebTestClient client;

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("bigbazaar.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @AfterAll
    static void stop() {
        JWKS_SERVER.stop(0);
    }

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
    void tokenSignedWithAnotherKeyIsRejected() {
        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token(ATTACKER_KEY, "bigbazaar-identity"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token(IDENTITY_KEY, "someone-else"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokenPassesTheEdgeAndIsRouted() {
        int status = client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token(IDENTITY_KEY, "bigbazaar-identity"))
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

    private static String token(RSAKey key, String issuer) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("CUSTOMER"))
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint(true).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stands in for identity-service's /.well-known/jwks.json. */
    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(IDENTITY_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
