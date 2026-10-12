package com.bigbazaar.identity.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the adapter against a local stand-in that answers like sandbox.co.in's documented API, so the
 * request shapes and response parsing are checked without real keys.
 */
class SandboxKycProviderTest {

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final AtomicInteger authCalls = new AtomicInteger();
    private SandboxKycProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new SandboxKycProvider(new KycProperties.Sandbox(URI.create(base), "key_test", "secret_test",
                null, Duration.ofSeconds(5)), JsonMapper.builder().build(), Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void verifiesGstinWithOneCachedToken() {
        var active = provider.verifyGstin("24ABKCS2033B1ZV");
        assertThat(active.active()).isTrue();
        assertThat(active.legalName()).isEqualTo("SANDBOX FINANCIAL TECHNOLOGIES PRIVATE LIMITED");
        assertThat(active.reference()).isEqualTo("txn-1");
        assertThat(bodies.get("/gst/compliance/public/gstin/verify")).contains("\"gstin\":\"24ABKCS2033B1ZV\"");

        var cancelled = provider.verifyGstin("27CANCELD1234A1Z5");
        assertThat(cancelled.active()).isFalse();
        assertThat(cancelled.status()).isEqualTo("Cancelled");
        assertThat(authCalls).hasValue(1);
    }

    @Test
    void sendsPanRequestInTheDocumentedShape() {
        var pan = provider.verifyPan("ABKCS2033B", "Sandbox Financial", "06/02/2023");
        assertThat(pan.valid()).isTrue();
        assertThat(pan.nameMatches()).isTrue();
        assertThat(pan.dateMatches()).isFalse();
        assertThat(bodies.get("/kyc/pan/verify")).contains("\"@entity\":\"in.co.sandbox.kyc.pan_verification.request\"",
                "\"consent\":\"Y\"", "\"date_of_birth\":\"06/02/2023\"", "\"reason\":\"Seller onboarding");

        var invalid = provider.verifyPan("ZZZZZ9999Z", "Nobody", "01/01/2000");
        assertThat(invalid.valid()).isFalse();
    }

    @Test
    void pennyDropReturnsTheNameAtTheBank() {
        var bank = provider.verifyBankAccount("SBIN0061411", "42090969996");
        assertThat(bank.accountExists()).isTrue();
        assertThat(bank.nameAtBank()).isEqualTo("John Doe");
        assertThat(calls).contains("GET /bank/SBIN0061411/accounts/42090969996/verify");
    }

    @Test
    void sourceOutagesAreRetryable() {
        assertThatThrownBy(() -> provider.verifyBankAccount("SBIN0061411", "50300000000"))
                .isInstanceOf(KycUnavailableException.class);
    }

    @Test
    void expiredTokenIsRenewedOnce() {
        provider.verifyGstin("24ABKCS2033B1ZV");
        provider.verifyGstin("401ONCE0000A1Z5"); // the stand-in rejects the token once
        assertThat(authCalls).hasValue(2);
    }

    @Test
    void readsTheAadhaarNameAfterDigiLockerSucceeds() {
        var session = provider.startIdentitySession();
        assertThat(session.sessionId()).isEqualTo("0454f149-26d0-41ef-9f14-c54f4b5f7b70");
        assertThat(bodies.get("/kyc/digilocker-sdk/sessions/create")).contains("\"doc_types\":[\"aadhaar\"]");

        var identity = provider.fetchIdentity(session.sessionId());
        assertThat(identity.completed()).isTrue();
        assertThat(identity.name()).isEqualTo("Kai Sharma");

        var pending = provider.fetchIdentity("not-finished");
        assertThat(pending.completed()).isFalse();
        assertThat(pending.sessionStatus()).isEqualTo("initialized");
    }

    @Test
    void aadhaarXmlWithADoctypeIsRefused() {
        byte[] hostile = """
                <?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
                <Poi name="&e;"/>""".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SandboxKycProvider.aadhaarName(hostile)).isInstanceOf(IllegalStateException.class);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        calls.add(ex.getRequestMethod() + " " + path);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.put(path, body);
        if (path.equals("/authenticate")) {
            assertThat(ex.getRequestHeaders().getFirst("x-api-secret")).isEqualTo("secret_test");
            int n = authCalls.incrementAndGet();
            respond(ex, 200, "{\"code\":200,\"data\":{\"access_token\":\"token-" + n + "\"}}");
            return;
        }
        if (path.equals("/files/aadhaar.xml")) { // a pre-signed storage URL: no API headers
            assertThat(ex.getRequestHeaders().getFirst("x-api-key")).isNull();
            byte[] xml = """
                    <?xml version="1.0" encoding="UTF-8"?><Certificate><CertificateData><KycRes><UidData>
                    <Poi name="Kai Sharma" dob="01-01-1990" gender="M"/></UidData></KycRes></CertificateData>
                    </Certificate>""".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, xml.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(xml);
            }
            return;
        }
        String token = ex.getRequestHeaders().getFirst("authorization");
        if (token == null || token.startsWith("Bearer") || !"key_test".equals(ex.getRequestHeaders()
                .getFirst("x-api-key"))) {
            respond(ex, 401, "{\"code\":401,\"message\":\"Unauthorized\"}");
            return;
        }
        if (body.contains("401ONCE") && token.equals("token-1")) {
            respond(ex, 403, "{\"code\":403,\"message\":\"Insufficient privilege\"}");
            return;
        }
        switch (path) {
            case "/gst/compliance/public/gstin/verify" -> respond(ex, 200, body.contains("CANCELD")
                    ? gst("Cancelled") : gst("Active"));
            case "/kyc/pan/verify" -> respond(ex, body.contains("ZZZZZ") ? 422 : 200, body.contains("ZZZZZ")
                    ? "{\"code\":422,\"message\":\"Invalid Pan pattern\",\"transaction_id\":\"txn-p\"}"
                    : """
                    {"code":200,"transaction_id":"txn-p","data":{"@entity":"in.co.sandbox.kyc.pan_verification.response",
                     "pan":"ABKCS2033B","category":"company","status":"valid","remarks":null,
                     "name_as_per_pan_match":true,"date_of_birth_match":false,"aadhaar_seeding_status":"na"}}""");
            case "/bank/SBIN0061411/accounts/42090969996/verify" -> respond(ex, 200, """
                    {"code":200,"transaction_id":"txn-b","data":{"message":"Bank Account details verified successfully.",
                     "account_exists":true,"name_at_bank":"John Doe","utr":"329613870973","amount_deposited":"1"}}""");
            case "/bank/SBIN0061411/accounts/50300000000/verify" ->
                    respond(ex, 503, "{\"code\":503,\"message\":\"Source Unavailable\"}");
            case "/kyc/digilocker-sdk/sessions/create" -> respond(ex, 200, """
                    {"code":200,"data":{"id":"0454f149-26d0-41ef-9f14-c54f4b5f7b70","status":"created"}}""");
            case "/kyc/digilocker-sdk/sessions/0454f149-26d0-41ef-9f14-c54f4b5f7b70/status" -> respond(ex, 200,
                    "{\"code\":200,\"data\":{\"status\":\"succeeded\"}}");
            case "/kyc/digilocker-sdk/sessions/not-finished/status" -> respond(ex, 200,
                    "{\"code\":200,\"data\":{\"status\":\"initialized\"}}");
            case "/kyc/digilocker-sdk/sessions/0454f149-26d0-41ef-9f14-c54f4b5f7b70/documents/aadhaar" ->
                    respond(ex, 200, "{\"code\":200,\"transaction_id\":\"txn-d\",\"data\":{\"files\":[{\"url\":\"http://127.0.0.1:"
                            + server.getAddress().getPort() + "/files/aadhaar.xml\"}]}}");
            default -> respond(ex, 404, "{}");
        }
    }

    private static String gst(String status) {
        return """
                {"code":200,"timestamp":1,"transaction_id":"txn-1","data":{"data":{
                 "legalName":"SANDBOX FINANCIAL TECHNOLOGIES PRIVATE LIMITED","validGstin":true,
                 "pan":"ABKCS2033B","gstin":"24ABKCS2033B1ZV","status":"%s"},"status_cd":"1"}}""".formatted(status);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
