package com.bigbazaar.identity.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development stand-in for S3 (SRS 2.5). Files go in a folder; upload and download links point at
 * {@link LocalStorageController} and carry an HMAC signature and expiry, mimicking pre-signed URLs,
 * so clients behave exactly as they will against real S3.
 */
public class LocalObjectStorage implements ObjectStorage {

    static final String PATH_PREFIX = "/api/v1/storage/objects/";
    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorage.class);
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,400}$");

    private final Path root;
    private final URI publicBaseUrl;
    private final byte[] signingKey;

    public LocalObjectStorage(StorageProperties.Local config) {
        if (config == null || config.directory() == null || config.publicBaseUrl() == null) {
            throw new IllegalStateException("bigbazaar.storage.local.directory and public-base-url are required");
        }
        this.root = config.directory().toAbsolutePath().normalize();
        this.publicBaseUrl = config.publicBaseUrl();
        if (config.signingKey() == null || config.signingKey().isBlank()) {
            this.signingKey = new byte[32];
            new SecureRandom().nextBytes(signingKey);
            log.warn("No bigbazaar.storage.local.signing-key set; using a random one. "
                    + "Links stop working on restart and can't be shared between replicas.");
        } else {
            this.signingKey = Base64.getDecoder().decode(config.signingKey());
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public UploadTarget presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        requireSafeKey(key);
        long expires = Instant.now().plus(ttl).getEpochSecond();
        String sig = sign("PUT", key, expires, contentType, sizeBytes);
        URI url = URI.create(publicBaseUrl + PATH_PREFIX + key + "?expires=" + expires + "&type="
                + URLEncoder.encode(contentType, StandardCharsets.UTF_8) + "&size=" + sizeBytes + "&sig=" + sig);
        return new UploadTarget(url, "PUT", Map.of("Content-Type", contentType), Instant.ofEpochSecond(expires));
    }

    @Override
    public URI presignDownload(String key, Duration ttl) {
        requireSafeKey(key);
        long expires = Instant.now().plus(ttl).getEpochSecond();
        return URI.create(publicBaseUrl + PATH_PREFIX + key + "?expires=" + expires + "&sig="
                + sign("GET", key, expires, "", 0));
    }

    @Override
    public Optional<StoredObject> head(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String type = Files.exists(meta(file)) ? Files.readString(meta(file)) : "application/octet-stream";
            return Optional.of(new StoredObject(Files.size(file), type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] readPrefix(String key, int length) {
        try (InputStream in = Files.newInputStream(resolve(key))) {
            return in.readNBytes(length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path file = resolve(key);
            Files.deleteIfExists(file);
            Files.deleteIfExists(meta(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean verifyUpload(String key, long expires, String contentType, long size, String sig) {
        return verify(sign("PUT", key, expires, contentType, size), sig, expires);
    }

    boolean verifyDownload(String key, long expires, String sig) {
        return verify(sign("GET", key, expires, "", 0), sig, expires);
    }

    /** Writes via a temp file so a half-finished upload is never visible. */
    void write(String key, String contentType, InputStream body) throws IOException {
        Path file = resolve(key);
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".upload-", ".tmp");
        try {
            Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(meta(file), contentType);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    Path resolve(String key) {
        requireSafeKey(key);
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Invalid key");
        }
        return file;
    }

    static boolean isSafeKey(String key) {
        return key != null && SAFE_KEY.matcher(key).matches() && !key.contains("..");
    }

    private static void requireSafeKey(String key) {
        if (!isSafeKey(key)) {
            throw new IllegalArgumentException("Invalid key");
        }
    }

    private static Path meta(Path file) {
        return file.resolveSibling(file.getFileName() + ".content-type");
    }

    private boolean verify(String expected, String actual, long expires) {
        return actual != null && Instant.now().getEpochSecond() <= expires
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String method, String key, long expires, String contentType, long size) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String canonical = method + "\n" + key + "\n" + expires + "\n" + contentType + "\n" + size;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
