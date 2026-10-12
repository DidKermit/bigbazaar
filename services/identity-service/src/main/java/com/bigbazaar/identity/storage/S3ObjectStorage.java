package com.bigbazaar.identity.storage;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * Pre-signed URLs carry a signature over the content type and exact length, so the store itself
 * refuses a different type or size from the one we approved.
 */
class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStorage(S3Client client, S3Presigner presigner, String bucket, boolean createBucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        if (createBucket) {
            ensureBucket();
        }
    }

    @Override
    public UploadTarget presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).contentLength(sizeBytes).build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                r -> r.signatureDuration(ttl).putObjectRequest(put));
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new UploadTarget(toUri(presigned.url()), "PUT", headers, presigned.expiration());
    }

    @Override
    public URI presignDownload(String key, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return toUri(presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(get)).url());
    }

    @Override
    public Optional<StoredObject> head(String key) {
        try {
            var head = client.headObject(r -> r.bucket(bucket).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public byte[] readPrefix(String key, int length) {
        return client.getObjectAsBytes(r -> r.bucket(bucket).key(key).range("bytes=0-" + (length - 1)))
                .asByteArray();
    }

    @Override
    public void delete(String key) {
        client.deleteObject(r -> r.bucket(bucket).key(key));
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            client.createBucket(r -> r.bucket(bucket));
            log.info("Created bucket {}", bucket);
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            client.createBucket(r -> r.bucket(bucket));
            log.info("Created bucket {}", bucket);
        }
    }

    private static URI toUri(java.net.URL url) {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

}
