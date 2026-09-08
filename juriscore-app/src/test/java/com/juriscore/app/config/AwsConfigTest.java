package com.juriscore.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a presigned URL is addressed, which is not a cosmetic question.
 *
 * <p>The S3 client and the S3 presigner are configured separately, and it is easy for the
 * two to disagree — which is exactly what happened here. The client was told to use
 * path-style addressing for local endpoints; the presigner was not, so it signed
 * {@code http://juriscore-documents.localhost:4566/...}. Every server-side call kept
 * working, because the client never used that form. Only the browser saw it, and browsers
 * do not resolve that hostname: the upload failed at the one step the server cannot
 * observe.
 *
 * <p>The URL cannot simply be rewritten after signing — the host is part of what is
 * signed — so the addressing style has to be decided at presigner construction, and that
 * is what these tests pin.
 */
class AwsConfigTest {

    private static final String LOCAL_ENDPOINT = "http://localhost:4566";
    private static final String BUCKET = "juriscore-documents";

    private static AwsProperties properties(String endpoint) {
        AwsProperties properties = new AwsProperties();
        properties.setRegion("ap-south-1");
        properties.setEndpoint(endpoint);
        // Local-only stand-ins; real deployments never read these (see AwsConfig).
        properties.setAccessKey("test");
        properties.setSecretKey("test");
        return properties;
    }

    private static URI presignedPut(AwsProperties properties) {
        AwsConfig config = new AwsConfig(properties);
        // Fixed credentials rather than config.awsCredentialsProvider(): with no endpoint
        // override that method returns the SDK's default chain, which would go looking for
        // real credentials on whatever machine runs this test. Nothing here is asserting
        // how credentials are resolved — only how the URL is addressed.
        S3Presigner presigner = config.s3Presigner(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")));
        try (presigner) {
            return presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(15))
                            .putObjectRequest(PutObjectRequest.builder()
                                    .bucket(BUCKET)
                                    .key("org/case/document.pdf")
                                    .contentType("application/pdf")
                                    .build())
                            .build())
                    .url()
                    .toURI();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a local presigned upload URL addresses the bucket by path, not by hostname")
    void localPresigningIsPathStyle() {
        URI url = presignedPut(properties(LOCAL_ENDPOINT));

        // The host is the endpoint itself — resolvable by any browser.
        assertThat(url.getHost()).isEqualTo("localhost");
        assertThat(url.getPort()).isEqualTo(4566);
        // The bucket moved into the path, where LocalStack expects it.
        assertThat(url.getPath()).startsWith("/" + BUCKET + "/");
        // And crucially not into the hostname, which is the form that fails in a browser.
        assertThat(url.getHost()).doesNotContain(BUCKET);
    }

    @Test
    @DisplayName("the signature covers the path-style host, so the URL cannot be rewritten later")
    void theSignedUrlIsUsableAsIssued() {
        URI url = presignedPut(properties(LOCAL_ENDPOINT));

        assertThat(url.getQuery())
                .as("a presigned URL carries its own SigV4 credentials")
                .contains("X-Amz-Signature")
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256");
        assertThat(url.getScheme()).isEqualTo("http");
    }

    @Test
    @DisplayName("with no endpoint override, real AWS virtual-host addressing is left alone")
    void awsPresigningStaysVirtualHosted() {
        // No endpoint means a real deployment. Path-style addressing is deprecated on AWS,
        // so the SDK default must survive this fix — the change is scoped to local only.
        URI url = presignedPut(properties(null));

        assertThat(url.getHost()).startsWith(BUCKET + ".s3");
        assertThat(url.getPath()).doesNotStartWith("/" + BUCKET + "/");
    }
}
