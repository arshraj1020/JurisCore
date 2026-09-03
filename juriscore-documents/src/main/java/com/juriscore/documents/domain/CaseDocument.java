package com.juriscore.documents.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A file attached to a matter — the description of one, at least. The bytes are in object
 * storage and have never been through this application.
 *
 * <p>{@link #storageKey} is the join between the two halves, and it is the one field a
 * caller can never influence: it is derived entirely from ids the platform generated, so
 * there is no string on the path that a user chose. That is what makes a traversal or a
 * cross-tenant collision impossible rather than merely filtered. It is also never returned
 * by the API — the bucket layout is not something clients should be able to reason about.
 *
 * <p>{@code caseId} points into {@code casework.cases} and so carries no foreign key,
 * following the same rule as hearings and tasks; it is validated through {@code CaseAccess}
 * before anything is written.
 */
@Entity
@Table(name = "case_documents", schema = "documents")
@Getter
@Setter
@NoArgsConstructor
public class CaseDocument extends TenantAwareEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    /** The name the person uploading recognises. Editable; never used as the object key. */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 255, updatable = false)
    private String contentType;

    /**
     * Declared when the link is issued, then replaced at completion with the size storage
     * actually reports. Not {@code updatable = false}, because that correction is the
     * whole point — but no API path writes it.
     */
    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /**
     * The object key, stamped immediately after the insert that generates the id it is
     * built from, and never changed again.
     *
     * <p>It is <em>not</em> mapped {@code updatable = false}, and that is deliberate
     * rather than an oversight. Hibernate excludes a non-updatable column from dirty
     * checking entirely, so the stamping statement was silently discarded and every row
     * kept its placeholder — the defect this mapping used to carry. What keeps a caller
     * from influencing the key is not the mapping: it is that {@code UpdateDocumentRequest}
     * has no field for it, {@code DocumentService.update} never writes it, and
     * {@code uk_case_documents_storage_key} would reject a collision if either ever
     * changed.
     */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DocumentStatus status;

    @Column(name = "description", length = 2000)
    private String description;

    /** When storage confirmed the object. Null for anything that never got that far. */
    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isDownloadable() {
        return status.isDownloadable() && !isDeleted();
    }

    /**
     * The only way a document changes status.
     *
     * <p>On the entity so the invariant travels with the object, and so the two timestamps
     * that shadow the status cannot drift from it — the database asserts the same pairing
     * in {@code ck_case_documents_uploaded_at} and {@code ck_case_documents_deleted_at}, so
     * a mistake here is a failed insert rather than a row that contradicts itself.
     */
    public void transitionTo(DocumentStatus target, Instant when) {
        DocumentStatusPolicy.requireTransition(this.status, target);
        this.status = target;
        if (target == DocumentStatus.AVAILABLE) {
            this.uploadedAt = when;
        }
        if (target == DocumentStatus.DELETED) {
            this.deletedAt = when;
        }
    }
}
