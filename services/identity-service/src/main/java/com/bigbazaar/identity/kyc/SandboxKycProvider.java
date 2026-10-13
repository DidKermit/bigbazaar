package com.bigbazaar.identity.kyc;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * sandbox.co.in (Quicko). Test keys work against {@code test-api.sandbox.co.in}. Every call needs a
 * 24-hour access token from {@code POST /authenticate}; it is cached and refreshed an hour early, or
 * at once if the API rejects it. The token goes in {@code Authorization} without "Bearer".
 */
public class SandboxKycProvider implements KycProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxKycProvider.class);
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(23);

    private final KycProperties.Sandbox config;
    private final RestClient http;
    private final RestClient downloads;
    private final JsonMapper json;
    private final Clock clock;
    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public SandboxKycProvider(KycProperties.Sandbox config, JsonMapper json, Clock clock) {
        if (config.apiKey() == null || config.apiKey().isBlank() || config.apiSecret() == null
                || config.apiSecret().isBlank()) {
            throw new IllegalStateException("bigbazaar.kyc.provider=sandbox needs SANDBOX_API_KEY and "
                    + "SANDBOX_API_SECRET (create them at https://console.sandbox.co.in)");
        }
        this.config = config;
        this.json = json;
        this.clock = clock;
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requestFactory.setReadTimeout(config.timeout());
        this.http = RestClient.builder().requestFactory(requestFactory).baseUrl(config.baseUrl().toString())
                .defaultHeader("x-api-key", config.apiKey())
                .defaultHeader("x-api-version", "1.0.0")
                .build();
        this.downloads = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public GstinResult verifyGstin(String gstin) {
        Reply reply = post("/gst/compliance/public/gstin/verify", Map.of("gstin", gstin));
        if (reply.rejected()) {
            return new GstinResult(false, reply.message(), null, reply.reference());
        }
        JsonNode data = reply.data().path("data");
        return new GstinResult(data.path("validGstin").asBoolean(true), text(data, "status"),
                text(data, "legalName"), reply.reference());
    }

    @Override
    public PanResult verifyPan(String pan, String nameAsPerPan, String dateOfBirthDdMmYyyy) {
        Reply reply = post("/kyc/pan/verify", Map.of(
                "@entity", "in.co.sandbox.kyc.pan_verification.request",
                "pan", pan,
                "name_as_per_pan", nameAsPerPan,
                "date_of_birth", dateOfBirthDdMmYyyy,
                "consent", "Y",
                "reason", config.reason()));
        if (reply.rejected()) {
            return new PanResult("invalid", false, false, null, reply.message(), reply.reference());
        }
        JsonNode data = reply.data();
        return new PanResult(text(data, "status"), data.path("name_as_per_pan_match").asBoolean(false),
                data.path("date_of_birth_match").asBoolean(false), text(data, "category"), text(data, "remarks"),
                reply.reference());
    }

    @Override
    public BankResult verifyBankAccount(String ifsc, String accountNumber) {
        Reply reply = get("/bank/{ifsc}/accounts/{account}/verify", ifsc, accountNumber);
        if (reply.rejected()) {
            return new BankResult(false, null, reply.message(), reply.reference());
        }
        JsonNode data = reply.data();
        return new BankResult(data.path("account_exists").asBoolean(false), text(data, "name_at_bank"),
                text(data, "message"), reply.reference());
    }

    @Override
    public IdentitySession startIdentitySession() {
        Reply reply = post("/kyc/digilocker-sdk/sessions/create", Map.of(
                "@entity", "in.co.sandbox.kyc.digilocker.sdk.session.request",
                "flow", "signin",
                "doc_types", List.of("aadhaar")));
        if (reply.rejected()) {
            throw new KycUnavailableException("DigiLocker session was refused: " + reply.message());
        }
        return new IdentitySession(text(reply.data(), "id"));
    }

    @Override
    public IdentityResult fetchIdentity(String sessionId) {
        Reply status = get("/kyc/digilocker-sdk/sessions/{id}/status", sessionId);
        String sessionStatus = status.rejected() ? "unknown" : text(status.data(), "status");
        if (!"succeeded".equalsIgnoreCase(sessionStatus)) {
            return new IdentityResult(false, sessionStatus, null, status.reference());
        }
        Reply document = get("/kyc/digilocker-sdk/sessions/{id}/documents/{type}", sessionId, "aadhaar");
        JsonNode files = document.data().path("files");
        if (document.rejected() || !files.isArray() || files.isEmpty()) {
            return new IdentityResult(false, "no-document", null, document.reference());
        }
        String url = text(files.get(0), "url");
        byte[] xml = downloads.get().uri(URI.create(url)).retrieve().body(byte[].class);
        return new IdentityResult(true, sessionStatus, aadhaarName(xml), document.reference());
    }

    /** Aadhaar XML (UIDAI) carries the holder's name as {@code <Poi name="...">}. */
    static String aadhaarName(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            NodeList poi = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml))
                    .getElementsByTagName("Poi");
            return poi.getLength() == 0 ? null : ((Element) poi.item(0)).getAttribute("name");
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable Aadhaar document", e);
        }
    }

    private Reply post(String path, Object body) {
        return call(() -> http.post().uri(path).header("authorization", token())
                .contentType(MediaType.APPLICATION_JSON).body(write(body)).retrieve().body(String.class));
    }

    private Reply get(String path, Object... variables) {
        return call(() -> http.get().uri(path, variables).header("authorization", token())
                .retrieve().body(String.class));
    }

    /**
     * 4xx other than auth is a definite "no" from the registry (bad number, not found) and is returned
     * as a rejection; 5xx, timeouts and auth trouble are temporary and thrown for a retry.
     */
    private Reply call(java.util.function.Supplier<String> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return parse(request.get(), false);
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                if ((status.value() == 401 || status.value() == 403) && attempt == 1) {
                    tokenExpiresAt = Instant.EPOCH;
                    continue;
                }
                if (status.is4xxClientError() && status.value() != 401 && status.value() != 403
                        && status.value() != 429) {
                    return parse(e.getResponseBodyAsString(), true);
                }
                throw new KycUnavailableException("Sandbox returned " + status.value(), e);
            } catch (ResourceAccessException e) {
                throw new KycUnavailableException("Sandbox unreachable: " + e.getMessage(), e);
            }
        }
    }

    private String token() {
        if (clock.instant().isAfter(tokenExpiresAt)) {
            synchronized (this) {
                if (clock.instant().isAfter(tokenExpiresAt)) {
                    String body = http.post().uri("/authenticate").header("x-api-secret", config.apiSecret())
                            .retrieve().body(String.class);
                    token = json.readTree(body).path("data").path("access_token").asString();
                    tokenExpiresAt = clock.instant().plus(TOKEN_LIFETIME);
                    log.info("Obtained Sandbox access token");
                }
            }
        }
        return token;
    }

    private Reply parse(String body, boolean rejected) {
        JsonNode root = body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        // Sandbox also reports some failures in the body of a 200 (code 422, 503...).
        int code = root.path("code").asInt(rejected ? 400 : 200);
        if (code >= 500) {
            throw new KycUnavailableException("Sandbox source unavailable: " + text(root, "message"));
        }
        return new Reply(rejected || code >= 400, root.path("data"), text(root, "message"),
                text(root, "transaction_id"));
    }

    private String write(Object body) {
        return json.writeValueAsString(body);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private record Reply(boolean rejected, JsonNode data, String message, String reference) {
    }
}
