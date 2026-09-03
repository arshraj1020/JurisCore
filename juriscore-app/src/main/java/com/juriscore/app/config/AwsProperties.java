package com.juriscore.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AWS wiring, bound from {@code juriscore.aws.*}.
 *
 * <p>{@code endpoint} is what makes LocalStack work: set it locally and the same SDK
 * calls hit the container instead of AWS. Left empty in deployed environments, where
 * the default endpoint and the task role's credentials apply.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "juriscore.aws")
public class AwsProperties {

    private boolean enabled = true;

    private String region = "ap-south-1";

    /** Override for LocalStack, e.g. {@code http://localhost:4566}. Empty means real AWS. */
    private String endpoint;

    /** Only read when an endpoint override is present; real deployments use the task role. */
    private String accessKey = "test";

    private String secretKey = "test";

    /** Bucket holding case documents (PRD §17). */
    private String documentBucket = "juriscore-documents";

    /** Queue that notification consumers read from (PRD §20). */
    private String notificationQueue = "juriscore-notifications";

    private String auditQueue = "juriscore-audit";

    /**
     * How long an upload link stays valid.
     *
     * <p>Short on purpose. A presigned URL is a bearer credential for one object, so the
     * window is the blast radius if it leaks through a browser history, a proxy log or a
     * screenshot. Fifteen minutes is long enough to pick a file and push it over a poor
     * connection, and short enough that a leaked link is usually already dead.
     */
    private Duration uploadUrlExpiry = Duration.ofMinutes(15);

    /** Shorter still: a download link is clicked immediately or not at all. */
    private Duration downloadUrlExpiry = Duration.ofMinutes(5);
}
