package com.juriscore.app.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * S3 and SQS clients, pointed at LocalStack locally and at real AWS everywhere else.
 *
 * <p>The clients are wired now, ahead of the modules that use them (documents in
 * Phase 4, notifications in Phase 5), so that local infrastructure, credentials and
 * endpoint handling are proven before any feature depends on them.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
@ConditionalOnProperty(prefix = "juriscore.aws", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class AwsConfig {

    private final AwsProperties properties;

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        if (usesLocalEndpoint()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
        }
        // ECS task role / instance profile / environment, in the SDK's usual order.
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public S3Client s3Client(AwsCredentialsProvider credentialsProvider) {
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider);
        if (usesLocalEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    // LocalStack serves buckets by path, not by virtual host.
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    /** Used to hand clients time-limited download links instead of proxying file bytes (PRD §33). */
    @Bean
    public S3Presigner s3Presigner(AwsCredentialsProvider credentialsProvider) {
        var builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider);
        if (usesLocalEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    // The same path-style setting the client above uses, and for the same
                    // reason — but it matters more here, because the presigner's output is
                    // handed to a *browser*. Without it the SDK signs a virtual-host URL:
                    // http://juriscore-documents.localhost:4566/... — a hostname that
                    // LocalStack serves but that most resolvers and Chrome will not resolve,
                    // so the upload fails in the browser while every server-side call
                    // succeeds. Signing and addressing have to agree: a URL rewritten after
                    // signing breaks the signature, so the style must be chosen here.
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true).build());
        }
        // Real AWS keeps the SDK default (virtual-host addressing), which is what S3
        // recommends and what path-style deprecation requires.
        return builder.build();
    }

    @Bean
    public SqsClient sqsClient(AwsCredentialsProvider credentialsProvider) {
        var builder = SqsClient.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider);
        if (usesLocalEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    private boolean usesLocalEndpoint() {
        return properties.getEndpoint() != null && !properties.getEndpoint().isBlank();
    }
}
