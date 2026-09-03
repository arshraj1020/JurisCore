package com.juriscore.documents;

import com.juriscore.app.storage.InMemoryObjectStorageService;
import com.juriscore.casework.AbstractCaseworkIT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scaffolding for the Phase 4 integration tests.
 *
 * <p>Storage is {@link InMemoryObjectStorageService}, selected by
 * {@code juriscore.aws.enabled=false} in the test profile. It stores no bytes and its URLs
 * point nowhere — but every rule the service applies runs for real against it, including
 * the completion check, which fails exactly as S3 would when the client never uploaded.
 * {@link #simulateUpload} is how a test says "the browser finished its PUT".
 *
 * <p>No AWS credentials, no LocalStack, no network.
 */
abstract class AbstractDocumentIT extends AbstractCaseworkIT {

    protected static final String PDF = "application/pdf";

    @Autowired
    protected InMemoryObjectStorageService storage;

    @BeforeEach
    void emptyTheBucket() {
        storage.clear();
    }

    /** A firm with a client and a matter — the starting point for every document test. */
    protected record Matter(Firm firm, String clientId, String caseId) {
    }

    protected Matter openMatter(String firmName, String email) throws Exception {
        Firm firm = registerFirm(firmName, email);
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");
        return new Matter(firm, clientId, caseId);
    }

    protected String documentBody(String filename, String contentType, long size) {
        return """
                {"filename":"%s","contentType":"%s","fileSize":%d,"description":"Filed on 1 September"}
                """.formatted(filename, contentType, size);
    }

    /** Registers a document and returns its id, ignoring the link. */
    protected String register(String token, String caseId, String filename, long size)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cases/" + caseId + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody(filename, PDF, size)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("document").path("id").asText();
    }

    /**
     * Stands in for the browser having PUT the file to the presigned URL.
     *
     * <p>The key is read from the database rather than from the API, because the API never
     * returns it — which is itself one of the things these tests assert.
     */
    protected void simulateUpload(String documentId, long size, String contentType) {
        storage.put(storageKeyOf(documentId), size, contentType);
    }

    protected String storageKeyOf(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_key FROM documents.case_documents WHERE id = ?::uuid",
                String.class, documentId);
    }

    protected String statusOf(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM documents.case_documents WHERE id = ?::uuid",
                String.class, documentId);
    }

    protected long versionOf(String documentId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM documents.case_documents WHERE id = ?::uuid",
                Long.class, documentId);
        return version == null ? -1 : version;
    }

    /** Registers, uploads and completes — a document that is actually downloadable. */
    protected String availableDocument(String token, String caseId, String filename, long size)
            throws Exception {
        String documentId = register(token, caseId, filename, size);
        simulateUpload(documentId, size, PDF);
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        return documentId;
    }

    protected UUID organizationOf(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT organization_id FROM documents.case_documents WHERE id = ?::uuid",
                UUID.class, documentId);
    }
}
