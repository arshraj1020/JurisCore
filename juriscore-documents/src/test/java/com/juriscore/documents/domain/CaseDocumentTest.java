package com.juriscore.documents.domain;

import com.juriscore.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two timestamps that shadow the status. Both are also database check constraints, so
 * a bug here is a failed insert rather than a row that contradicts itself; these tests
 * assert the Java half.
 */
class CaseDocumentTest {

    private static final Instant WHEN = Instant.parse("2026-09-01T10:15:30Z");
    private CaseDocument document;

    @BeforeEach
    void register() {
        document = new CaseDocument();
        document.setOrganizationId(UUID.randomUUID());
        document.setCaseId(UUID.randomUUID());
        document.setOriginalFilename("Written statement.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(1024);
        document.setStorageKey("organizations/x/cases/y/documents/z");
        document.setStatus(DocumentStatus.UPLOADING);
    }

    @Test
    void confirmingAnUploadStampsTheUploadTime() {
        document.transitionTo(DocumentStatus.AVAILABLE, WHEN);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.AVAILABLE);
        assertThat(document.getUploadedAt()).isEqualTo(WHEN);
        assertThat(document.getDeletedAt()).isNull();
        assertThat(document.isDownloadable()).isTrue();
    }

    @Test
    @DisplayName("a failed upload gets no upload time — it never happened")
    void failingLeavesNoUploadTime() {
        document.transitionTo(DocumentStatus.FAILED, WHEN);

        assertThat(document.getUploadedAt()).isNull();
        assertThat(document.isDownloadable()).isFalse();
    }

    @Test
    void deletingStampsTheDeletionAndKeepsTheUploadTime() {
        document.transitionTo(DocumentStatus.AVAILABLE, WHEN);
        document.transitionTo(DocumentStatus.DELETED, WHEN.plusSeconds(60));

        assertThat(document.getDeletedAt()).isEqualTo(WHEN.plusSeconds(60));
        assertThat(document.getUploadedAt())
                .as("when it arrived is still true after it was removed")
                .isEqualTo(WHEN);
        assertThat(document.isDeleted()).isTrue();
        assertThat(document.isDownloadable()).isFalse();
    }

    @Test
    void aRefusedTransitionChangesNothing() {
        document.transitionTo(DocumentStatus.DELETED, WHEN);

        assertThatThrownBy(() -> document.transitionTo(DocumentStatus.AVAILABLE, WHEN))
                .isInstanceOf(ApiException.class);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.DELETED);
        assertThat(document.getDeletedAt()).isEqualTo(WHEN);
    }

    @Test
    @DisplayName("an unconfirmed document is not downloadable however recently it was registered")
    void anUploadingDocumentIsNotDownloadable() {
        assertThat(document.isDownloadable()).isFalse();
    }
}
