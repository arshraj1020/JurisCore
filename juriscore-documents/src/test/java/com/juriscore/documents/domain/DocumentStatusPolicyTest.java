package com.juriscore.documents.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The document lifecycle, cell by cell. */
class DocumentStatusPolicyTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "UPLOADING, AVAILABLE",
            "UPLOADING, FAILED",
            "UPLOADING, DELETED",
            "AVAILABLE, DELETED",
            "FAILED,    DELETED"
    })
    void permitsTheFiveLegalMoves(DocumentStatus from, DocumentStatus to) {
        assertThat(DocumentStatusPolicy.permits(from, to)).isTrue();
        assertThatCode(() -> DocumentStatusPolicy.requireTransition(from, to))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            // Deleted is the end: the object is gone, so undeleting would leave metadata
            // pointing at nothing.
            "DELETED,   UPLOADING", "DELETED, AVAILABLE", "DELETED, FAILED", "DELETED, DELETED",
            // A confirmed document cannot go back to being an unconfirmed one.
            "AVAILABLE, UPLOADING", "AVAILABLE, FAILED", "AVAILABLE, AVAILABLE",
            // A failed upload is not retried in place; a new document is registered.
            "FAILED,    UPLOADING", "FAILED, AVAILABLE", "FAILED, FAILED",
            "UPLOADING, UPLOADING"
    })
    void refusesEverythingElse(DocumentStatus from, DocumentStatus to) {
        assertThat(DocumentStatusPolicy.permits(from, to)).isFalse();
        assertThatThrownBy(() -> DocumentStatusPolicy.requireTransition(from, to))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("AVAILABLE -> AVAILABLE is refused; the service handles retries by not calling this")
    void aSecondCompletionIsNotATransition() {
        assertThat(DocumentStatusPolicy.permits(DocumentStatus.AVAILABLE, DocumentStatus.AVAILABLE))
                .as("a permissive policy here would hide double completion rather than handle it")
                .isFalse();
    }

    @Test
    @DisplayName("an abandoned upload can be removed without pretending it succeeded")
    void anUploadingDocumentCanBeDeletedDirectly() {
        assertThat(DocumentStatusPolicy.permits(DocumentStatus.UPLOADING, DocumentStatus.DELETED))
                .isTrue();
    }

    @Test
    void deletedIsTerminal() {
        assertThat(DocumentStatusPolicy.allowedFrom(DocumentStatus.DELETED)).isEmpty();
        assertThat(DocumentStatus.DELETED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("only AVAILABLE yields a download")
    void onlyAvailableIsDownloadable() {
        assertThat(DocumentStatus.AVAILABLE.isDownloadable()).isTrue();
        assertThat(DocumentStatus.UPLOADING.isDownloadable()).isFalse();
        assertThat(DocumentStatus.FAILED.isDownloadable()).isFalse();
        assertThat(DocumentStatus.DELETED.isDownloadable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(DocumentStatus.class)
    @DisplayName("every status has an entry, so a new one cannot be added and forgotten")
    void everyStatusIsCovered(DocumentStatus status) {
        Set<DocumentStatus> allowed = DocumentStatusPolicy.allowedFrom(status);
        assertThat(allowed).isNotNull().doesNotContain(status);
        if (status.isTerminal()) {
            assertThat(allowed).isEmpty();
        } else {
            assertThat(allowed).isNotEmpty();
        }
    }

    @Test
    void refusesNulls() {
        assertThat(DocumentStatusPolicy.permits(null, DocumentStatus.AVAILABLE)).isFalse();
        assertThat(DocumentStatusPolicy.permits(DocumentStatus.UPLOADING, null)).isFalse();
    }
}
