package com.bigbazaar.identity.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {

    @TempDir
    Path dir;

    private LocalObjectStorage storage() {
        return new LocalObjectStorage(new StorageProperties.Local(dir, URI.create("http://gw.test"), null));
    }

    @Test
    void uploadLinkVerifiesOnlyForTheSignedKeyTypeAndSize() {
        LocalObjectStorage storage = storage();
        var target = storage.presignUpload("sellers/a/documents/b.pdf", "application/pdf", 42, Duration.ofMinutes(5));
        Map<String, String> q = query(target.url());
        long expires = Long.parseLong(q.get("expires"));

        assertThat(storage.verifyUpload("sellers/a/documents/b.pdf", expires, q.get("type"), 42, q.get("sig"))).isTrue();
        assertThat(storage.verifyUpload("sellers/a/documents/b.pdf", expires, "text/html", 42, q.get("sig"))).isFalse();
        assertThat(storage.verifyUpload("sellers/a/documents/b.pdf", expires, q.get("type"), 43, q.get("sig"))).isFalse();
        assertThat(storage.verifyUpload("sellers/x/documents/b.pdf", expires, q.get("type"), 42, q.get("sig"))).isFalse();
        assertThat(storage.verifyDownload("sellers/a/documents/b.pdf", expires, q.get("sig"))).isFalse();
    }

    @Test
    void expiredLinksFail() {
        LocalObjectStorage storage = storage();
        var target = storage.presignUpload("k.pdf", "application/pdf", 1, Duration.ofSeconds(-5));
        Map<String, String> q = query(target.url());
        assertThat(storage.verifyUpload("k.pdf", Long.parseLong(q.get("expires")), q.get("type"), 1, q.get("sig")))
                .isFalse();
    }

    @Test
    void writeHeadReadDelete() throws Exception {
        LocalObjectStorage storage = storage();
        storage.write("sellers/a/doc.png", "image/png", new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        assertThat(storage.head("sellers/a/doc.png")).hasValueSatisfying(o -> {
            assertThat(o.sizeBytes()).isEqualTo(4);
            assertThat(o.contentType()).isEqualTo("image/png");
        });
        assertThat(storage.readPrefix("sellers/a/doc.png", 2)).containsExactly(1, 2);
        storage.delete("sellers/a/doc.png");
        assertThat(storage.head("sellers/a/doc.png")).isEmpty();
    }

    @Test
    void refusesPathTraversal() {
        assertThat(LocalObjectStorage.isSafeKey("../etc/passwd")).isFalse();
        assertThat(LocalObjectStorage.isSafeKey("a/../../b")).isFalse();
        assertThat(LocalObjectStorage.isSafeKey("/abs")).isFalse();
        assertThatThrownBy(() -> storage().resolve("a/../../b")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] kv = part.split("=", 2);
            out.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }
}
