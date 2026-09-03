package com.juriscore.documents.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the platform accepts.
 *
 * <p>All of it runs before a link is issued, and none of it trusts the caller — which
 * matters more here than almost anywhere else, because the object itself is written by the
 * browser straight to S3 and is never seen by this application.
 */
class DocumentUploadPolicyTest {

    private DocumentProperties properties;
    private DocumentUploadPolicy policy;

    @BeforeEach
    void configure() {
        properties = new DocumentProperties();
        properties.setMaxFileSize(1_000_000L);
        properties.setMaxFilenameLength(255);
        policy = new DocumentUploadPolicy(properties);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((ApiException) t).errorCode();
    }

    // ------------------------------------------------------------------------ filenames

    @Test
    void acceptsAnOrdinaryFilename() {
        assertThatCode(() -> policy.validateFilename("Written statement.pdf"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "../../../etc/passwd",
            "..",
            "reply/../../secret.pdf",
            "folder/reply.pdf",
            "folder\\reply.pdf",
            "/etc/passwd",
            "C:\\Windows\\system32\\config",
            "."
    })
    @DisplayName("a name that looks like a path is refused")
    void refusesPathsAndTraversal(String filename) {
        assertThatThrownBy(() -> policy.validateFilename(filename))
                .isInstanceOf(ApiException.class)
                .extracting(DocumentUploadPolicyTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a name cannot smuggle a newline into the Content-Disposition header")
    void refusesControlCharacters() {
        assertThatThrownBy(() -> policy.validateFilename("reply.pdf\r\nX-Evil: 1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validateFilename("reply\u0000.pdf"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validateFilename("reply\u007F.pdf"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void refusesAMissingOrBlankFilename() {
        assertThatThrownBy(() -> policy.validateFilename(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validateFilename("   ")).isInstanceOf(ApiException.class);
    }

    @Test
    void refusesAFilenameLongerThanTheColumn() {
        assertThatCode(() -> policy.validateFilename("a".repeat(255))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateFilename("a".repeat(256)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("unicode and spaces are fine — this is a filename, not an identifier")
    void acceptsOrdinaryHumanFilenames() {
        assertThatCode(() -> policy.validateFilename("Menon v. Iyer — written statement (final).pdf"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateFilename("वकालतनामा.pdf")).doesNotThrowAnyException();
    }

    // --------------------------------------------------------------------- content types

    @Test
    void acceptsAnAllowlistedType() {
        assertThatCode(() -> policy.validateContentType("application/pdf"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the charset parameter does not stop a type matching the allowlist")
    void normalisesParametersAndCase() {
        assertThatCode(() -> policy.validateContentType("TEXT/PLAIN; charset=utf-8"))
                .doesNotThrowAnyException();
        assertThat(DocumentUploadPolicy.normalise("TEXT/PLAIN; charset=utf-8")).isEqualTo("text/plain");
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "application/x-msdownload",
            "application/x-sh",
            "text/html",
            "image/svg+xml",
            "application/javascript",
            "application/octet-stream"
    })
    @DisplayName("executable and script-ish types are not on the list, so they are refused")
    void refusesAnythingNotAllowlisted(String contentType) {
        assertThatThrownBy(() -> policy.validateContentType(contentType))
                .isInstanceOf(ApiException.class)
                .extracting(DocumentUploadPolicyTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("an allowlist, not a blocklist: an unknown type is refused rather than allowed")
    void refusesUnknownTypesByDefault() {
        assertThat(policy.isAllowed("application/vnd.some-future-format")).isFalse();
    }

    @Test
    void refusesAMissingContentType() {
        assertThatThrownBy(() -> policy.validateContentType(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validateContentType("  ")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the extension is not consulted — a .pdf name with a refused type is refused")
    void theExtensionIsNotEvidence() {
        assertThatThrownBy(() -> policy.validate("harmless.pdf", "application/x-sh", 10))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------------------- sizes

    @Test
    void acceptsASizeInsideTheCeiling() {
        assertThatCode(() -> policy.validateFileSize(1_000_000L)).doesNotThrowAnyException();
    }

    @Test
    void refusesZeroAndNegativeSizes() {
        assertThatThrownBy(() -> policy.validateFileSize(0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validateFileSize(-1)).isInstanceOf(ApiException.class);
    }

    @Test
    void refusesAnythingOverTheCeiling() {
        assertThatThrownBy(() -> policy.validateFileSize(1_000_001L))
                .isInstanceOf(ApiException.class)
                .extracting(DocumentUploadPolicyTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void reportsTheConfiguredCeilingSoCompletionCanRecheckIt() {
        assertThat(policy.maxFileSize()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("validate() checks all three, and the filename first")
    void validateAppliesEveryRule() {
        // Not "../evil": that contains a separator, so the separator rule fires first and
        // the message names that instead. Both refuse it; this pins the '..' rule itself.
        assertThatThrownBy(() -> policy.validate("..evil", "application/pdf", 10))
                .hasMessageContaining("..");
        assertThatThrownBy(() -> policy.validate("ok.pdf", "text/html", 10))
                .hasMessageContaining("not accepted");
        assertThatThrownBy(() -> policy.validate("ok.pdf", "application/pdf", 0))
                .hasMessageContaining("greater than zero");
        assertThatCode(() -> policy.validate("ok.pdf", "application/pdf", 10))
                .doesNotThrowAnyException();
    }
}
