package com.bigbazaar.identity.storage;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the signed links issued by {@link LocalObjectStorage}. The signature is the authorization,
 * the same way it is for an S3 pre-signed URL. Only active with {@code provider=local}.
 */
@RestController
@ConditionalOnProperty(prefix = "bigbazaar.storage", name = "provider", havingValue = "local", matchIfMissing = true)
class LocalStorageController {

    private final LocalObjectStorage storage;

    LocalStorageController(ObjectStorage storage) {
        this.storage = (LocalObjectStorage) storage;
    }

    @PutMapping(LocalObjectStorage.PATH_PREFIX + "**")
    ResponseEntity<Void> upload(HttpServletRequest request,
                                @RequestParam long expires, @RequestParam String type, @RequestParam long size,
                                @RequestParam String sig,
                                @RequestHeader(value = "Content-Type", required = false) String contentType)
            throws IOException {
        String key = keyOf(request);
        if (key == null || !storage.verifyUpload(key, expires, type, size, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!sameType(type, contentType)) {
            // S3 rejects a mismatched signed header the same way.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        long declared = request.getContentLengthLong();
        if (declared >= 0 && declared != size) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // A proxy may re-send the body chunked, so the size is also checked on what actually arrived.
        try (InputStream body = request.getInputStream()) {
            storage.write(key, type, new BoundedInputStream(body, size));
        } catch (IOException e) {
            storage.delete(key);
            return ResponseEntity.badRequest().build();
        }
        if (storage.head(key).map(o -> o.sizeBytes() != size).orElse(true)) {
            storage.delete(key);
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping(LocalObjectStorage.PATH_PREFIX + "**")
    ResponseEntity<FileSystemResource> download(HttpServletRequest request, @RequestParam long expires,
                                                @RequestParam String sig) {
        String key = keyOf(request);
        if (key == null || !storage.verifyDownload(key, expires, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path file = storage.resolve(key);
        var head = storage.head(key);
        if (head.isEmpty() || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(head.get().contentType()))
                .contentLength(head.get().sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }

    private static boolean sameType(String signed, String header) {
        if (header == null) {
            return false;
        }
        try {
            return MediaType.parseMediaType(signed).equalsTypeAndSubtype(MediaType.parseMediaType(header));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String keyOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int at = uri.indexOf(LocalObjectStorage.PATH_PREFIX);
        if (at < 0) {
            return null;
        }
        String key = uri.substring(at + LocalObjectStorage.PATH_PREFIX.length());
        return LocalObjectStorage.isSafeKey(key) ? key : null;
    }

    /** Fails if the body is longer than the signed size, so a client can't stream more than approved. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && --remaining < 0) {
                throw new IOException("Body larger than signed size");
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) {
                remaining -= n;
                if (remaining < 0) {
                    throw new IOException("Body larger than signed size");
                }
            }
            return n;
        }
    }
}
