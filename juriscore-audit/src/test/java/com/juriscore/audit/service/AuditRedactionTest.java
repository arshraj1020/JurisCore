package com.juriscore.audit.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard on what an audit summary may contain.
 *
 * <p>The producers already keep secrets off their events — no event carries a presigned
 * URL, and the two that carry tokens are audited without them. This is the layer that
 * assumes one day one of them will change.
 */
class AuditRedactionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Invoice INV-2026-000001 issued for 11800.00 INR, due 2026-03-31",
            "Client added: Sharma Textiles Pvt Ltd",
            "Password reset requested for asha@sharma-legal.test",
            "Invited Ravi Kulkarni <ravi@sharma-legal.test> as LAWYER",
            "Document upload confirmed at 20481 bytes: Written statement.pdf",
            "Payment of 4000.00 INR recorded against INV-2026-000001; 7800.00 outstanding",
            "Matter CASE-2026-000001 moved from OPEN to IN_PROGRESS"})
    @DisplayName("the summaries the listener actually writes all pass")
    void realSummariesAreAccepted(String summary) {
        assertThat(AuditRedaction.isSafe(summary)).isTrue();
        assertThat(AuditRedaction.require(summary)).isEqualTo(summary);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // A presigned S3 link, which is a bearer credential for one document.
            "Download issued: https://bucket.s3.amazonaws.com/x?X-Amz-Signature=abcdef123456",
            "Link: https://bucket.s3.amazonaws.com/x?X-Amz-Credential=AKIAIOSFODNN7EXAMPLE",
            "callback Signature=9f8e7d6c5b4a",
            // A JWT, in each of the places one turns up.
            "Token issued: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.abc",
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9abcdefgh",
            // AWS credentials.
            "Using key AKIAIOSFODNN7EXAMPLE",
            "aws_secret_access_key rotated",
            // Anything labelled as a credential.
            "reset with password=Hunter2!!",
            "api_key: abcdef",
            "refresh_token = xyz",
            // A document object key.
            "Purged organizations/11111111-1111-1111-1111-111111111111/cases/x",
            // A card-shaped digit run.
            "Paid by card 4111 1111 1111 1111",
            "Account 4111111111111111"})
    @DisplayName("anything that looks like a secret is refused rather than scrubbed")
    void secretsAreRefused(String summary) {
        assertThat(AuditRedaction.isSafe(summary)).isFalse();
        assertThatThrownBy(() -> AuditRedaction.require(summary))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential");
    }

    @Test
    @DisplayName("failing loudly, not scrubbing: a tripped rule is a bug in whatever built the summary")
    void refusalIsLoud() {
        assertThatThrownBy(() -> AuditRedaction.require("password=hunter2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bug in the listener");
    }

    @Test
    void refusesAnEmptySummary() {
        assertThatThrownBy(() -> AuditRedaction.require(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRedaction.require("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an over-long summary is truncated to the column, not rejected")
    void longSummariesAreTruncated() {
        String long1 = "Client added: " + "x".repeat(1000);
        assertThat(AuditRedaction.require(long1)).hasSize(AuditRedaction.MAX_SUMMARY);
    }

    @Test
    @DisplayName("an invoice number is not mistaken for a card number")
    void invoiceNumbersSurvive() {
        assertThat(AuditRedaction.isSafe("Invoice INV-2026-000001 issued")).isTrue();
        assertThat(AuditRedaction.isSafe("Matter CASE-2026-000042 opened")).isTrue();
    }

    @Test
    @DisplayName("a UUID is not a credential — half the summaries contain one")
    void uuidsSurvive() {
        assertThat(AuditRedaction.isSafe(
                "Lawyer 3f2504e0-4f89-11d3-9a0c-0305e82c3301 assigned to CASE-2026-000001"))
                .isTrue();
    }
}
