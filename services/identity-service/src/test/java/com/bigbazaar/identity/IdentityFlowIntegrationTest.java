package com.bigbazaar.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.outbox.OutboxRelay;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Phase 1 acceptance path against real PostgreSQL and Kafka. */
@SpringBootTest(properties = {
        "bigbazaar.jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes",
        "bigbazaar.bootstrap-admin.email=admin@bigbazaar.test",
        "bigbazaar.bootstrap-admin.password=AdminPass123",
        "bigbazaar.outbox.poll-interval=1h",
        "logging.level.org.apache.kafka=WARN"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class IdentityFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRelay relay;
    @Autowired KafkaConnectionDetails kafka;

    @Test
    void customerBecomesApprovedSellerAndEventsArePublished() throws Exception {
        String email = "kai+" + UUID.randomUUID() + "@example.com";
        String correlationId = UUID.randomUUID().toString();

        // Register (FR-AUTH-01) and duplicate rejection
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", correlationId)
                        .content(body(Map.of("name", "Kai", "email", email, "phone", "9876543210",
                                "password", "Secret1234"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", correlationId))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "Kai", "email", email.toUpperCase(), "phone", "9876543210",
                                "password", "Secret1234"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.success").value(false));

        // Password is stored as an adaptive hash (FR-AUTH-02)
        String hash = jdbc.queryForObject("select password_hash from users where lower(email) = lower(?)",
                String.class, email);
        assertThat(hash).startsWith("$2a$12$");

        // Login, profile, refresh rotation (FR-AUTH-03, FR-AUTH-04)
        JsonNode tokens = login(email, "Secret1234");
        String access = tokens.get("accessToken").asString();
        String refresh = tokens.get("refreshToken").asString();
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        JsonNode rotated = refresh(refresh, 200);
        refresh(refresh, 401); // replay of a rotated token...
        refresh(rotated.get("refreshToken").asString(), 401); // ...revokes the whole family

        tokens = login(email, "Secret1234");
        access = tokens.get("accessToken").asString();

        // Non-admin cannot reach admin APIs (FR-AUTH-05)
        mvc.perform(get("/api/v1/admin/sellers").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Apply as seller (FR-SELLER-01)
        MvcResult applied = mvc.perform(post("/api/v1/sellers/applications")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("businessName", "Kai Crafts", "businessEmail", "shop@example.com",
                                "businessPhone", "9876543210", "taxId", "29ABCDE1234F1Z5",
                                "pickupAddress", "12 MG Road, Bengaluru 560001"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andReturn();
        String sellerId = json.readTree(applied.getResponse().getContentAsString()).get("id").asString();

        // Admin approves with a reason (FR-SELLER-02); history is recorded (FR-SELLER-03)
        String adminAccess = login("admin@bigbazaar.test", "AdminPass123").get("accessToken").asString();
        mvc.perform(post("/api/v1/admin/sellers/{id}/decisions", sellerId)
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("decision", "APPROVE", "reason", "KYC documents verified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/v1/admin/sellers/{id}/decisions", sellerId)
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("decision", "APPROVE", "reason", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));

        mvc.perform(get("/api/v1/admin/sellers/{id}", sellerId).header("Authorization", "Bearer " + adminAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[1].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$.history[1].reason").value("KYC documents verified"));

        // New login carries the SELLER role
        JsonNode sellerTokens = login(email, "Secret1234");
        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + sellerTokens.get("accessToken").asString()))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.hasItems("CUSTOMER", "SELLER")));

        // Suspension blocks the account immediately, even with a still-valid access token (FR-AUTH-06)
        String userId = jdbc.queryForObject("select id::text from users where lower(email) = lower(?)",
                String.class, email);
        mvc.perform(post("/api/v1/admin/users/{id}/suspend", userId)
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("reason", "Chargeback fraud investigation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + sellerTokens.get("accessToken").asString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        refresh(sellerTokens.get("refreshToken").asString(), 401);

        // Events reach Kafka through the outbox (FR-AUTH-08, FR-SELLER-06)
        relay.publishPending();
        List<String> types = readEventTypes(4);
        assertThat(types).contains("UserRegistered", "SellerApplicationSubmitted", "SellerApproved", "UserSuspended");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where status <> 'PUBLISHED'",
                Integer.class)).isZero();
    }

    @Test
    void sellerStatusHistoryIsAppendOnly() {
        UUID userId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, name, email, phone, password_hash, status, created_at, updated_at)
                values (?, 'History Test', ?, '9876543210', 'x', 'ACTIVE', now(), now())""",
                userId, "history+" + userId + "@example.com");
        jdbc.update("""
                insert into sellers (id, user_id, business_name, business_email, business_phone, pickup_address,
                                     status, created_at, updated_at)
                values (?, ?, 'History Shop', 'h@example.com', '9876543210', 'Somewhere', 'PENDING_VERIFICATION',
                        now(), now())""", sellerId, userId);
        jdbc.update("""
                insert into seller_status_history (seller_id, to_status, reason, actor_id, changed_at)
                values (?, 'PENDING_VERIFICATION', 'Application submitted', ?, now())""", sellerId, userId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("update seller_status_history set reason = 'tampered' where seller_id = ?",
                                sellerId))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("delete from seller_status_history where seller_id = ?", sellerId))
                .hasMessageContaining("append-only");
    }

    @Test
    void repeatedWrongPasswordsLockTheAccount() throws Exception {
        String email = "lock+" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "Lock Test", "email", email, "phone", "9876543210",
                                "password", "Secret1234"))))
                .andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("email", email, "password", "wrong-password-1"))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", "Secret1234"))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void unauthenticatedRequestGetsStandardError() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("refreshToken", refreshToken))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String body(Map<String, ?> values) {
        return json.writeValueAsString(values);
    }

    private List<String> readEventTypes(int atLeast) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> types = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(Topics.IDENTITY_EVENTS));
            long deadline = System.currentTimeMillis() + 20_000;
            while (types.size() < atLeast && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    assertThat(envelope.get("eventId").asString()).isNotBlank();
                    assertThat(envelope.get("sourceService").asString()).isEqualTo("identity-service");
                    types.add(envelope.get("eventType").asString());
                }
            }
        }
        return types;
    }
}
