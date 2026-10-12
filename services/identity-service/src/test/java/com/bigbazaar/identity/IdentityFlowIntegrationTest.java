package com.bigbazaar.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.common.IdentityEvents;
import com.bigbazaar.identity.mfa.Totp;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Phase 1 acceptance path against real PostgreSQL and Kafka. */
@SpringBootTest(properties = {
        "bigbazaar.bootstrap-admin.email=admin@bigbazaar.test",
        "bigbazaar.bootstrap-admin.password=AdminPass123",
        "bigbazaar.outbox.poll-interval=1h",
        "bigbazaar.kyc.poll-interval=200ms",
        "bigbazaar.kyc.retry-delay=100ms",
        "bigbazaar.kyc.max-attempts=2",
        "logging.level.org.apache.kafka=WARN"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class IdentityFlowIntegrationTest {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("bigbazaar.jwt.private-key-location", () -> "file:" + TestKeys.PRIVATE_KEY);
        registry.add("bigbazaar.jwt.previous-public-key-locations", () -> "file:" + TestKeys.PREVIOUS_PUBLIC_KEY);
        registry.add("bigbazaar.mfa.encryption-key", () -> TestKeys.MFA_KEY);
        registry.add("bigbazaar.storage.provider", () -> "local");
        registry.add("bigbazaar.storage.local.directory", () -> TestKeys.STORAGE_DIR.toString());
        registry.add("bigbazaar.storage.local.public-base-url", () -> "http://gateway.test");
    }

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

        // The bootstrap admin exists
        assertThat(jdbc.queryForObject("""
                select count(*) from users u join user_roles r on r.user_id = u.id
                where u.email = 'admin@bigbazaar.test' and r.role = 'ADMIN'""", Integer.class)).isOne();

        // Admin without MFA can log in but cannot act as admin until enrolled
        String adminEmail = newAdmin();
        JsonNode adminLogin = login(adminEmail, "Secret1234");
        assertThat(adminLogin.get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(adminLogin.get("mfaEnrollmentRequired").asBoolean()).isTrue();
        String pwdOnlyAdmin = adminLogin.get("accessToken").asString();
        assertThat(amr(pwdOnlyAdmin)).containsExactly("pwd");
        mvc.perform(get("/api/v1/admin/sellers").header("Authorization", "Bearer " + pwdOnlyAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));

        // Enroll an authenticator app, then log in with password + code
        byte[] adminSecret = enrollTotp(pwdOnlyAdmin, "Secret1234");
        String adminAccess = loginWithTotp(adminEmail, "Secret1234", adminSecret);
        assertThat(amr(adminAccess)).containsExactly("pwd", "mfa");

        // Admin approves with a reason (FR-SELLER-02); history is recorded (FR-SELLER-03)
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
        List<String> types = readEventTypes(5);
        assertThat(types).contains("UserRegistered", "MfaEnabled", "SellerApplicationSubmitted", "SellerApproved",
                "UserSuspended");
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

    @Test
    void customerMfaLifecycle() throws Exception {
        String email = "mfa+" + UUID.randomUUID() + "@example.com";
        register(email);
        String pwdOnly = login(email, "Secret1234").get("accessToken").asString();

        // Setup needs the password, so a stolen access token alone can't enroll someone else's phone
        mvc.perform(post("/api/v1/users/me/mfa/totp/setup").header("Authorization", "Bearer " + pwdOnly)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("password", "wrong-pass1"))))
                .andExpect(status().isUnauthorized());

        MvcResult setup = mvc.perform(post("/api/v1/users/me/mfa/totp/setup")
                        .header("Authorization", "Bearer " + pwdOnly)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("password", "Secret1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/BigBazaar:")))
                .andReturn();
        byte[] secret = base32Decode(json.readTree(setup.getResponse().getContentAsString()).get("secret").asString());

        mvc.perform(post("/api/v1/users/me/mfa/totp/activate").header("Authorization", "Bearer " + pwdOnly)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("code", "000000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));
        long step = Totp.stepAt(java.time.Instant.now());
        MvcResult activated = mvc.perform(post("/api/v1/users/me/mfa/totp/activate")
                        .header("Authorization", "Bearer " + pwdOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("code", Totp.codeAt(secret, step)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();
        String recoveryCode = json.readTree(activated.getResponse().getContentAsString())
                .get("recoveryCodes").get(0).asString();

        // Password alone now only yields a challenge
        JsonNode challenge = login(email, "Secret1234");
        assertThat(challenge.get("status").asString()).isEqualTo("MFA_REQUIRED");
        assertThat(challenge.has("accessToken")).isFalse();
        String mfaToken = challenge.get("mfaToken").asString();

        // The code used for activation can't be replayed
        verifyMfa(mfaToken, Map.of("code", Totp.codeAt(secret, step)), 401);

        // A recovery code works once
        JsonNode viaRecovery = verifyMfa(mfaToken, Map.of("recoveryCode", recoveryCode.toLowerCase()), 200);
        assertThat(amr(viaRecovery.get("accessToken").asString())).containsExactly("pwd", "mfa");
        verifyMfa(mfaToken, Map.of("recoveryCode", recoveryCode), 401); // challenge already consumed
        String second = login(email, "Secret1234").get("mfaToken").asString();
        verifyMfa(second, Map.of("recoveryCode", recoveryCode), 401);    // code already used

        // Refreshing keeps the MFA strength of the original login
        JsonNode refreshed = refresh(viaRecovery.get("refreshToken").asString(), 200);
        assertThat(amr(refreshed.get("accessToken").asString())).containsExactly("pwd", "mfa");

        // A challenge dies after 5 wrong codes, even if the 6th is right
        String third = login(email, "Secret1234").get("mfaToken").asString();
        for (int i = 0; i < 5; i++) {
            verifyMfa(third, Map.of("code", "000000"), 401);
        }
        verifyMfa(third, Map.of("code", Totp.codeAt(secret, step + 1)), 401);

        // Admin reset removes MFA and signs the user out everywhere
        String userId = jdbc.queryForObject("select id::text from users where lower(email) = lower(?)",
                String.class, email);
        String admin = adminAccessToken();
        mvc.perform(post("/api/v1/admin/users/{id}/mfa/reset", userId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("reason", "Lost phone, identity checked on call"))))
                .andExpect(status().isNoContent());
        refresh(refreshed.get("refreshToken").asString(), 401);
        assertThat(login(email, "Secret1234").get("status").asString()).isEqualTo("AUTHENTICATED");
    }

    @Test
    void sellerUploadsKycDocumentAndAdminCanViewIt() throws Exception {
        String email = "docs+" + UUID.randomUUID() + "@example.com";
        register(email);
        String access = login(email, "Secret1234").get("accessToken").asString();
        MvcResult applied = mvc.perform(post("/api/v1/sellers/applications").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("businessName", "Docs Shop", "businessEmail", "docs@example.com",
                                "businessPhone", "9876543210", "pickupAddress", "1 Park Street, Kolkata"))))
                .andExpect(status().isCreated()).andReturn();
        String sellerId = json.readTree(applied.getResponse().getContentAsString()).get("id").asString();

        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes();
        JsonNode upload = requestUpload(access, "GST_CERTIFICATE", "application/pdf", pdf.length, "../../gst.pdf");
        String documentId = upload.get("document").get("id").asString();
        assertThat(upload.get("document").get("status").asString()).isEqualTo("PENDING_UPLOAD");
        assertThat(upload.get("document").get("fileName").asString()).isEqualTo("gst.pdf");
        String uploadUrl = upload.get("upload").get("url").asString();
        assertThat(uploadUrl).startsWith("http://gateway.test/api/v1/storage/objects/sellers/" + sellerId + "/");

        // Confirming before the file arrives is refused
        mvc.perform(post("/api/v1/sellers/me/documents/{id}/confirm", documentId)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));

        // The signed link refuses a different content type or a tampered signature
        mvc.perform(put(localPath(uploadUrl)).contentType("text/html").content(pdf)).andExpect(status().isForbidden());
        mvc.perform(put(java.net.URI.create(localPath(uploadUrl).toString().replace("&sig=", "&sig=x"))).contentType("application/pdf").content(pdf))
                .andExpect(status().isForbidden());

        mvc.perform(put(localPath(uploadUrl)).contentType("application/pdf").content(pdf)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/sellers/me/documents/{id}/confirm", documentId)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        // A file pretending to be a PDF is rejected and deleted
        byte[] fake = "MZ-not-really-a-pdf".getBytes();
        JsonNode fakeUpload = requestUpload(access, "PAN", "application/pdf", fake.length, "pan.pdf");
        mvc.perform(put(localPath(fakeUpload.get("upload").get("url").asString()))
                        .contentType("application/pdf").content(fake))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/sellers/me/documents/{id}/confirm",
                        fakeUpload.get("document").get("id").asString())
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));

        // Unsupported types and oversized files are refused up front
        mvc.perform(post("/api/v1/sellers/me/documents/uploads").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("documentType", "PAN", "contentType", "application/zip",
                                "sizeBytes", 100))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/sellers/me/documents/uploads").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("documentType", "PAN", "contentType", "application/pdf",
                                "sizeBytes", 11 * 1024 * 1024))))
                .andExpect(status().isBadRequest());

        // Another seller can't touch it
        String otherEmail = "other+" + UUID.randomUUID() + "@example.com";
        register(otherEmail);
        String other = login(otherEmail, "Secret1234").get("accessToken").asString();
        mvc.perform(get("/api/v1/sellers/me/documents/{id}/link", documentId).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());

        // Admin sees both documents with their status and can open the valid one
        String admin = adminAccessToken();
        mvc.perform(get("/api/v1/admin/sellers/{id}", sellerId).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.documents[1].status").value("INVALID"));
        MvcResult link = mvc.perform(get("/api/v1/admin/sellers/{s}/documents/{d}/link", sellerId, documentId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        String viewUrl = json.readTree(link.getResponse().getContentAsString()).get("url").asString();
        MvcResult file = mvc.perform(get(localPath(viewUrl)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(pdf);
        // ...and the upload link can't be reused to read it
        mvc.perform(get(localPath(uploadUrl))).andExpect(status().isForbidden());
    }

    private JsonNode requestUpload(String access, String type, String contentType, long size, String fileName)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sellers/me/documents/uploads")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("documentType", type, "contentType", contentType, "sizeBytes", size,
                                "fileName", fileName))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** The link points at the gateway; MockMvc talks to identity directly, so keep only path and query. */
    private static java.net.URI localPath(String url) {
        return java.net.URI.create(url.substring("http://gateway.test".length()));
    }

    @Test
    void jwksPublishesOnlyThePublicKey() throws Exception {
        MvcResult result = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andReturn();
        JsonNode keys = json.readTree(result.getResponse().getContentAsString()).get("keys");
        assertThat(keys.size()).isEqualTo(2); // current key first, then the retired one
        keys.forEach(key -> {
            assertThat(key.has("d")).isFalse();
            assertThat(key.has("p")).isFalse();
        });
    }

    @Test
    void tokensFromTheRetiredKeyStayValidAfterRotation() throws Exception {
        String email = "rotation+" + UUID.randomUUID() + "@example.com";
        register(email);
        String current = login(email, "Secret1234").get("accessToken").asString();
        String userId = json.readTree(new String(java.util.Base64.getUrlDecoder().decode(current.split("\\.")[1])))
                .get("sub").asString();

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + current))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + signedToken(TestKeys.PREVIOUS_KEY, userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + signedToken(TestKeys.newKeyPair(), userId)))
                .andExpect(status().isUnauthorized());
    }

    /** A token as identity would have issued it, signed with the given key pair. */
    private static String signedToken(java.security.KeyPair keyPair, String userId) throws Exception {
        var publicKey = (java.security.interfaces.RSAPublicKey) keyPair.getPublic();
        var jwk = new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey)
                .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
                .keyID(new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey).build().computeThumbprint().toString())
                .build();
        var now = java.time.Instant.now();
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("bigbazaar-identity").subject(userId)
                .issueTime(java.util.Date.from(now)).expirationTime(java.util.Date.from(now.plusSeconds(300)))
                .claim("roles", List.of("CUSTOMER")).claim("amr", List.of("pwd")).build();
        var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                com.nimbusds.jose.JWSAlgorithm.RS256).keyID(jwk.getKeyID()).build(), claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(jwk));
        return jwt.serialize();
    }

    @Test
    void cleanApplicationIsApprovedAutomatically() throws Exception {
        String email = "auto+" + UUID.randomUUID() + "@example.com";
        register(email);
        String access = login(email, "Secret1234").get("accessToken").asString();
        String sellerId = applyForVerification(access, "29ABCDE1234F1Z5", "123456789012");

        mvc.perform(get("/api/v1/sellers/me").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.verification.status").value("AWAITING_IDENTITY"))
                .andExpect(jsonPath("$.verification.nextStep").value("CONNECT_DIGILOCKER"))
                .andExpect(jsonPath("$.seller.bankAccountLast4").value("9012"));
        // Checks can't run before DigiLocker is connected.
        mvc.perform(post("/api/v1/sellers/me/verification/identity/complete")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isConflict());
        connectDigiLocker(access);

        JsonNode detail = awaitSellerStatus(access, "APPROVED");
        assertThat(detail.at("/verification/status").asString()).isEqualTo("PASSED");
        List<String> outcomes = new ArrayList<>();
        detail.at("/verification/checks").forEach(c -> outcomes.add(c.get("type").asString() + "="
                + c.get("outcome").asString()));
        assertThat(outcomes).containsExactly("GSTIN=PASS", "PAN=PASS", "BANK_ACCOUNT=PASS", "IDENTITY=PASS");
        JsonNode last = detail.get("history").get(detail.get("history").size() - 1);
        assertThat(last.get("actorId").asString()).isEqualTo(IdentityEvents.SYSTEM_ACTOR_ID.toString());
        assertThat(last.get("reason").asString()).startsWith("Automatic verification passed");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = ?",
                Long.class, sellerId, IdentityEvents.SELLER_VERIFICATION_COMPLETED)).isEqualTo(1);
        // The bank account number itself is never stored readable.
        assertThat(jdbc.queryForObject("select kyc_inputs_enc from sellers where id = ?::uuid", String.class,
                sellerId)).startsWith("v1:").doesNotContain("123456789012");

        mvc.perform(get("/api/v1/users/me").header("Authorization",
                        "Bearer " + login(email, "Secret1234").get("accessToken").asString()))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.hasItem("SELLER")));
    }

    @Test
    void mismatchesGoToAnAdminWithTheResults() throws Exception {
        String email = "mismatch+" + UUID.randomUUID() + "@example.com";
        register(email);
        String access = login(email, "Secret1234").get("accessToken").asString();
        // Mock registry: a PAN ending in X has a different name; an account ending 1111 is someone else's.
        String sellerId = applyForVerification(access, "29ABCDE1234X1Z5", "999999991111");
        connectDigiLocker(access);

        JsonNode detail = awaitSellerStatus(access, "UNDER_REVIEW");
        assertThat(detail.at("/verification/status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(detail.at("/verification/checks/1/outcome").asString()).isEqualTo("MISMATCH");
        assertThat(detail.at("/verification/checks/2/registryName").asString()).isEqualTo("SOMEONE ELSE");
        assertThat(detail.at("/verification/outcomeSummary").asString())
                .contains("Name on PAN doesn't match", "held by a different name");

        String admin = adminAccessToken();
        mvc.perform(get("/api/v1/admin/sellers/{id}", sellerId).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.verification.checks.length()").value(4));
        mvc.perform(post("/api/v1/admin/sellers/{id}/decisions", sellerId)
                        .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("decision", "APPROVE", "reason", "Checked the PAN card by phone"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void providerOutageIsRetriedThenHandedToAnAdmin() throws Exception {
        String email = "outage+" + UUID.randomUUID() + "@example.com";
        register(email);
        String access = login(email, "Secret1234").get("accessToken").asString();
        applyForVerification(access, "29ABCDE1234F1Z5", "123456785030"); // mock bank network is down
        connectDigiLocker(access);

        JsonNode detail = awaitSellerStatus(access, "UNDER_REVIEW");
        assertThat(detail.at("/verification/attempts").asInt()).isEqualTo(2);
        assertThat(detail.at("/verification/checks/0/outcome").asString()).isEqualTo("ERROR");
    }

    private String applyForVerification(String access, String gstin, String account) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sellers/applications")
                        .header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("businessName", "Kai Crafts", "businessEmail", "shop@example.com",
                                "businessPhone", "9876543210", "taxId", gstin,
                                "pickupAddress", "12 MG Road, Bengaluru", "panDate", "01/01/1990",
                                "bankAccountNumber", account, "bankIfsc", "SBIN0001234"))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private void connectDigiLocker(String access) throws Exception {
        mvc.perform(post("/api/v1/sellers/me/verification/identity").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("mock"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty());
        mvc.perform(post("/api/v1/sellers/me/verification/identity/complete")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    private JsonNode awaitSellerStatus(String access, String expected) {
        JsonNode[] detail = new JsonNode[1];
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
            MvcResult r = mvc.perform(get("/api/v1/sellers/me").header("Authorization", "Bearer " + access))
                    .andReturn();
            detail[0] = json.readTree(r.getResponse().getContentAsString());
            return expected.equals(detail[0].at("/seller/status").asString());
        });
        return detail[0];
    }

    /** A fresh admin (role granted directly in the DB) so tests don't share MFA state. */
    private String newAdmin() throws Exception {
        String email = "admin+" + UUID.randomUUID() + "@example.com";
        register(email);
        jdbc.update("insert into user_roles (user_id, role) select id, 'ADMIN' from users where email = ?", email);
        return email;
    }

    private String adminAccessToken() throws Exception {
        String email = newAdmin();
        byte[] secret = enrollTotp(login(email, "Secret1234").get("accessToken").asString(), "Secret1234");
        return loginWithTotp(email, "Secret1234", secret);
    }

    private byte[] enrollTotp(String accessToken, String password) throws Exception {
        MvcResult setup = mvc.perform(post("/api/v1/users/me/mfa/totp/setup")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("password", password))))
                .andExpect(status().isOk())
                .andReturn();
        byte[] secret = base32Decode(json.readTree(setup.getResponse().getContentAsString()).get("secret").asString());
        mvc.perform(post("/api/v1/users/me/mfa/totp/activate").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("code", Totp.codeAt(secret, Totp.stepAt(java.time.Instant.now()))))))
                .andExpect(status().isOk());
        return secret;
    }

    /** Uses the next time step so it never collides with a code just used for enrollment. */
    private String loginWithTotp(String email, String password, byte[] secret) throws Exception {
        JsonNode challenge = login(email, password);
        assertThat(challenge.get("status").asString()).isEqualTo("MFA_REQUIRED");
        String code = Totp.codeAt(secret, Totp.stepAt(java.time.Instant.now()) + 1);
        return verifyMfa(challenge.get("mfaToken").asString(), Map.of("code", code), 200)
                .get("accessToken").asString();
    }

    private JsonNode verifyMfa(String mfaToken, Map<String, String> factor, int expectedStatus) throws Exception {
        var request = new java.util.HashMap<String, String>(factor);
        request.put("mfaToken", mfaToken);
        MvcResult result = mvc.perform(post("/api/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(body(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "Test User", "email", email, "phone", "9876543210",
                                "password", "Secret1234"))))
                .andExpect(status().isCreated());
    }

    private List<String> amr(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]));
        List<String> values = new ArrayList<>();
        json.readTree(payload).get("amr").forEach(n -> values.add(n.asString()));
        return values;
    }

    private static byte[] base32Decode(String value) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : value.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
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
