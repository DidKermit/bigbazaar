package com.bigbazaar.identity.storage;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bigbazaar.storage")
public record StorageProperties(
        /* "local" (development) or "s3" (AWS S3, Cloudflare R2, MinIO, SeaweedFS...). */
        String provider,
        Local local,
        S3 s3
) {
    public StorageProperties {
        if (provider == null) {
            provider = "local";
        }
    }

    /**
     * @param directory     where files are written
     * @param publicBaseUrl how clients reach identity's storage endpoint, normally through the gateway
     * @param signingKey    base64 HMAC key for upload/download links; random per start if empty
     */
    public record Local(Path directory, URI publicBaseUrl, String signingKey) {
    }

    /**
     * @param endpoint       S3 API endpoint the service uses; empty for AWS
     * @param publicEndpoint endpoint baked into links handed to browsers, if different (e.g. Docker
     *                       service name inside, localhost outside); empty means same as endpoint
     */
    public record S3(String bucket, String region, URI endpoint, URI publicEndpoint, String accessKey,
                     String secretKey, boolean pathStyle, boolean createBucket) {
    }
}
