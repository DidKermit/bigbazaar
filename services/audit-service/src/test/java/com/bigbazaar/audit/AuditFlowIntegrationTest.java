package com.bigbazaar.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bigbazaar.common.events.Topics;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Audit pipeline against real Kafka and PostgreSQL, with identity's JWKS stood in by a local server. */
@SpringBootTest(properties = {
        "bigbazaar.audit.retry-interval=100ms",
        "logging.level.org.apache.kafka=WARN"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuditFlowIntegrationTest {

    private static final RSAKey IDENTITY_KEY = newKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("bigbazaar.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/.well-known/jwks.json");
    }

    @AfterAll
    static void stop() {
        JWKS_SERVER.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaConnection;

    @Test
    void identityEventIsRecordedOnceAndSearchableByAdmins() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String event = """
                {"eventId":"%s","eventType":"SellerApproved","eventVersion":1,"sourceService":"identity-service",
                 "correlationId":"%s","occurredAt":"%s",
                 "payload":{"sellerId":"%s","userId":"%s","actorId":"%s","fromStatus":"UNDER_REVIEW",
                            "toStatus":"APPROVED","reason":"Documents verified","email":"seller@example.com"}}"""
                .formatted(eventId, correlationId, Instant.now(), sellerId, UUID.randomUUID(), adminId);

        // Delivered twice, as Kafka may do after a rebalance: recorded once.
        kafka.send(Topics.IDENTITY_EVENTS, sellerId.toString(), event).get();
        kafka.send(Topics.IDENTITY_EVENTS, sellerId.toString(), event).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> count(eventId) == 1
                && jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class,
                eventId) == 1);
        Thread.sleep(1000);
        assertThat(count(eventId)).isEqualTo(1);

        mvc.perform(get("/api/v1/admin/audit-events").param("entityType", "Seller")
                        .param("entityId", sellerId.toString())
                        .header("Authorization", "Bearer " + token(List.of("ADMIN"), List.of("pwd", "mfa"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].eventType").value("SellerApproved"))
                .andExpect(jsonPath("$.content[0].actorId").value(adminId.toString()))
                .andExpect(jsonPath("$.content[0].reason").value("Documents verified"))
                .andExpect(jsonPath("$.content[0].correlationId").value(correlationId.toString()))
                .andExpect(jsonPath("$.content[0].details.toStatus").value("APPROVED"))
                .andExpect(jsonPath("$.content[0].details.email").doesNotExist());

        mvc.perform(get("/api/v1/admin/audit-events").param("correlationId", correlationId.toString())
                        .header("Authorization", "Bearer " + token(List.of("ADMIN"), List.of("pwd", "mfa"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value(eventId.toString()));
    }

    @Test
    void auditRowsCannotBeChangedOrDeleted() {
        UUID eventId = UUID.randomUUID();
        kafka.send(Topics.IDENTITY_EVENTS, """
                {"eventId":"%s","eventType":"UserRegistered","eventVersion":1,"sourceService":"identity-service",
                 "occurredAt":"%s","payload":{"userId":"%s"}}"""
                .formatted(eventId, Instant.now(), UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(30)).until(() -> count(eventId) == 1);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET reason = 'edited' WHERE event_id = ?", eventId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE event_id = ?", eventId))
                .hasMessageContaining("append-only");
    }

    @Test
    void unreadableMessagesAreParkedOnTheDeadLetterTopic() {
        String marker = "garbage-" + UUID.randomUUID();
        kafka.send(Topics.IDENTITY_EVENTS, marker, "{not json " + marker);
        kafka.send(Topics.IDENTITY_EVENTS, marker, """
                {"eventType":"MissingId","sourceService":"x","occurredAt":"%s","payload":{},"tag":"%s"}"""
                .formatted(Instant.now(), marker));

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.deadLetter(Topics.IDENTITY_EVENTS)));
            var parked = new java.util.ArrayList<String>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (marker.equals(r.key())) {
                        parked.add(r.value());
                    }
                });
                return parked.size() == 2;
            });
        }
        // The listener kept going: a good event after the bad ones is still recorded.
        UUID eventId = UUID.randomUUID();
        kafka.send(Topics.IDENTITY_EVENTS, marker, """
                {"eventId":"%s","eventType":"UserRegistered","eventVersion":1,"sourceService":"identity-service",
                 "occurredAt":"%s","payload":{"userId":"%s"}}"""
                .formatted(eventId, Instant.now(), UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(30)).until(() -> count(eventId) == 1);
    }

    @Test
    void searchNeedsAnAdminWhoUsedMfa() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token(List.of("CUSTOMER"), List.of("pwd", "mfa"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token(List.of("ADMIN"), List.of("pwd"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token(List.of("ADMIN"), List.of("pwd", "mfa"))))
                .andExpect(status().isOk());
    }

    private long count(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_id = ?", Long.class, eventId);
    }

    private static String token(List<String> roles, List<String> amr) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(IDENTITY_KEY)));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("bigbazaar-identity")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("roles", roles)
                .claim("amr", amr)
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(IDENTITY_KEY.getKeyID()).build();
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
