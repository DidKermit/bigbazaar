package com.bigbazaar.identity.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the S3 adapter against Adobe S3Mock, a real S3 API in a container. Pre-signed links are used
 * exactly as a browser would: plain HTTP with the returned headers.
 */
@Testcontainers
class S3ObjectStorageTest {

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:4.7.0")
            .withExposedPorts(9090)
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "kyc-test");

    private final HttpClient http = HttpClient.newHttpClient();

    private ObjectStorage storage() {
        URI endpoint = URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9090));
        var config = new StorageProperties.S3("kyc-test", "ap-south-1", endpoint, null, "test", "test", true, false);
        return new StorageConfig().objectStorage(new StorageProperties("s3", null, config));
    }

    @Test
    void uploadWithPresignedLinkThenHeadReadAndDownload() throws Exception {
        ObjectStorage storage = storage();
        byte[] pdf = "%PDF-1.7 test document".getBytes();
        var target = storage.presignUpload("sellers/s1/documents/d1.pdf", "application/pdf", pdf.length,
                Duration.ofMinutes(5));

        HttpRequest.Builder put = HttpRequest.newBuilder(target.url()).PUT(HttpRequest.BodyPublishers.ofByteArray(pdf));
        target.headers().forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length")) {
                put.header(name, value);
            }
        });
        assertThat(http.send(put.build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);

        var head = storage.head("sellers/s1/documents/d1.pdf").orElseThrow();
        assertThat(head.sizeBytes()).isEqualTo(pdf.length);
        assertThat(head.contentType()).isEqualTo("application/pdf");
        assertThat(new String(storage.readPrefix("sellers/s1/documents/d1.pdf", 5))).isEqualTo("%PDF-");

        var download = http.send(HttpRequest.newBuilder(
                        storage.presignDownload("sellers/s1/documents/d1.pdf", Duration.ofMinutes(1))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.body()).isEqualTo(pdf);

        storage.delete("sellers/s1/documents/d1.pdf");
        assertThat(storage.head("sellers/s1/documents/d1.pdf")).isEmpty();
    }

    @Test
    void signedLinkRefusesADifferentContentType() throws Exception {
        ObjectStorage storage = storage();
        byte[] body = "%PDF-1.7".getBytes();
        var target = storage.presignUpload("sellers/s1/documents/d2.pdf", "application/pdf", body.length,
                Duration.ofMinutes(5));
        var response = http.send(HttpRequest.newBuilder(target.url())
                        .header("Content-Type", "text/html")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(403);
    }
}
