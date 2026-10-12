package com.bigbazaar.identity.storage;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
class StorageConfig {

    @Bean
    ObjectStorage objectStorage(StorageProperties properties) {
        return switch (properties.provider()) {
            case "local" -> new LocalObjectStorage(properties.local());
            case "s3" -> s3(properties.s3());
            default -> throw new IllegalStateException(
                    "bigbazaar.storage.provider must be 'local' or 's3', was " + properties.provider());
        };
    }

    private static ObjectStorage s3(StorageProperties.S3 config) {
        if (config == null || config.bucket() == null || config.bucket().isBlank()) {
            throw new IllegalStateException("bigbazaar.storage.s3.bucket is required when provider is s3");
        }
        Region region = Region.of(config.region() == null ? "ap-south-1" : config.region());
        AwsCredentialsProvider credentials = config.accessKey() == null || config.accessKey().isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey(), config.secretKey()));
        S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(config.pathStyle()).build();

        var clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(s3Config);
        URI endpoint = blankToNull(config.endpoint());
        if (endpoint != null) {
            clientBuilder.endpointOverride(endpoint);
        }
        URI presignEndpoint = blankToNull(config.publicEndpoint()) != null ? config.publicEndpoint() : endpoint;
        var presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(s3Config);
        if (presignEndpoint != null) {
            presignerBuilder.endpointOverride(presignEndpoint);
        }
        return new S3ObjectStorage(clientBuilder.build(), presignerBuilder.build(), config.bucket(),
                config.createBucket());
    }

    private static URI blankToNull(URI uri) {
        return uri == null || uri.toString().isBlank() ? null : uri;
    }
}
