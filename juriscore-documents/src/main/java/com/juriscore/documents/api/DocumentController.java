package com.juriscore.documents.api;

import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.common.storage.ObjectStorageService;
import com.juriscore.documents.api.dto.CreateDocumentRequest;
import com.juriscore.documents.api.dto.DocumentDownloadResponse;
import com.juriscore.documents.api.dto.DocumentResponse;
import com.juriscore.documents.api.dto.DocumentUploadResponse;
import com.juriscore.documents.api.dto.UpdateDocumentRequest;
import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.domain.DocumentStatus;
import com.juriscore.documents.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Documents on a matter.
 *
 * <p>Roles follow the case-maintenance model Phases 2 and 3 established: all staff read,
 * upload, complete, download and rename; only {@code FIRM_ADMIN} removes, which is the
 * same shape as deleting a client, a task or a deadline. {@code CLIENT} and
 * {@code SUPER_ADMIN} appear in no list and are refused — a client portal would need the
 * explicit sharing mechanism that does not exist, and Phase 4 does not invent one.
 *
 * <p>No endpoint takes an organization id, and none returns a storage key. Bytes never
 * pass through here: uploads go to a presigned link and downloads come back as one.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Files attached to a matter, stored in object storage")
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping("/api/v1/cases/{caseId}/documents")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List the documents on a matter",
            description = "Newest first, tie-broken by id so paging is stable. Deleted "
                    + "documents are excluded.")
    public ApiResponse<PageResponse<DocumentResponse>> listForCase(
            @PathVariable UUID caseId,
            @RequestParam(required = false) DocumentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                documentService.listForCase(caseId, organizationId, status, pageable),
                DocumentResponse::from));
    }

    @PostMapping("/api/v1/cases/{caseId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Register a document and get a link to upload it to",
            description = "Returns metadata plus a short-lived presigned PUT URL. Upload the "
                    + "file to that URL with the Content-Type given, then call the complete "
                    + "endpoint — the document is not usable until storage has confirmed it.")
    public ApiResponse<DocumentUploadResponse> register(
            @PathVariable UUID caseId,
            @Valid @RequestBody CreateDocumentRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        DocumentService.UploadTicket ticket =
                documentService.register(caseId, organizationId, request);
        return ApiResponse.ok(uploadResponse(ticket), "Document registered successfully");
    }

    @PostMapping("/api/v1/documents/{documentId}/complete")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Confirm that the file has been uploaded",
            description = "Checks the object against storage rather than taking the caller's "
                    + "word, then moves the document to AVAILABLE. Completing an already "
                    + "complete document is a no-op, so retries are safe. A missing or "
                    + "oversized object is refused.")
    public ApiResponse<DocumentResponse> complete(@PathVariable UUID documentId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DocumentResponse.from(documentService.completeUpload(documentId, organizationId)),
                "Document upload completed successfully");
    }

    @GetMapping("/api/v1/documents/{documentId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one document's metadata")
    public ApiResponse<DocumentResponse> byId(@PathVariable UUID documentId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DocumentResponse.from(documentService.requireLive(documentId, organizationId)));
    }

    @GetMapping("/api/v1/documents/{documentId}/download")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Get a link to download the file",
            description = "A short-lived presigned GET URL, issued only after authorization "
                    + "and only for a document that is AVAILABLE. The file is never streamed "
                    + "through the application and never served from a permanent URL.")
    public ApiResponse<DocumentDownloadResponse> download(@PathVariable UUID documentId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        DocumentService.DownloadTicket ticket = documentService.download(documentId, organizationId);
        CaseDocument document = ticket.document();
        ObjectStorageService.PresignedUrl url = ticket.url();
        return ApiResponse.ok(new DocumentDownloadResponse(
                url.url(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getFileSize(),
                url.expiresAt(),
                url.validFor().toSeconds()));
    }

    @PutMapping("/api/v1/documents/{documentId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Rename a document or edit its description",
            description = "Only those two fields. Content type, size, status, case and "
                    + "storage location are properties of the stored file and cannot be "
                    + "edited. Send the version you last read; a stale one answers 409.")
    public ApiResponse<DocumentResponse> update(@PathVariable UUID documentId,
                                                @Valid @RequestBody UpdateDocumentRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DocumentResponse.from(documentService.update(documentId, organizationId, request)),
                "Document updated successfully");
    }

    @DeleteMapping("/api/v1/documents/{documentId}")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Remove a document from the matter",
            description = "The metadata is soft-deleted and the stored object is removed "
                    + "afterwards, once that has committed. The two are not atomic: if the "
                    + "object removal fails the document is still gone from the firm's view "
                    + "and the orphaned object is left for the bucket lifecycle rule.")
    public ApiResponse<DocumentResponse> delete(@PathVariable UUID documentId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DocumentResponse.from(documentService.delete(documentId, organizationId)),
                "Document deleted successfully");
    }

    private DocumentUploadResponse uploadResponse(DocumentService.UploadTicket ticket) {
        ObjectStorageService.PresignedUrl url = ticket.url();
        return new DocumentUploadResponse(
                DocumentResponse.from(ticket.document()),
                url.url(),
                "PUT",
                ticket.document().getContentType(),
                url.expiresAt(),
                url.validFor().toSeconds());
    }
}
