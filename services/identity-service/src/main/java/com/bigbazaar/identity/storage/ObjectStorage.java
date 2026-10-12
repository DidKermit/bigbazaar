package com.bigbazaar.identity.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Where uploaded files live (SRS 3.4: object storage, outside PostgreSQL). Clients never send file
 * bytes through our APIs: they get a short-lived upload URL and send the file straight to storage.
 * Implementations: {@link S3ObjectStorage} (AWS S3 or any S3-compatible store) and
 * {@link LocalObjectStorage} (a folder on disk, for local development; SRS 2.5).
 */
public interface ObjectStorage {

    /** A one-time URL the client PUTs the file to, with headers it must send exactly as given. */
    UploadTarget presignUpload(String key, String contentType, long sizeBytes, Duration ttl);

    /** A short-lived URL to view or download the file. */
    URI presignDownload(String key, Duration ttl);

    Optional<StoredObject> head(String key);

    /** The first {@code length} bytes, used to check a file really is the type it claims. */
    byte[] readPrefix(String key, int length);

    void delete(String key);

    record UploadTarget(URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record StoredObject(long sizeBytes, String contentType) {
    }
}
