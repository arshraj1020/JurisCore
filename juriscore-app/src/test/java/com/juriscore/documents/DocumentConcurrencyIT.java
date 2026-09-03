package com.juriscore.documents;

import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.event.DocumentUploadCompletedEvent;
import com.juriscore.documents.repository.CaseDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two people, one document.
 *
 * <p>Metadata edits are tested at both levels — over HTTP, where the second writer sends
 * the version it read, and in the database with two real transactions and a genuinely
 * stale entity. A mocked repository has no transaction to lose a race in, so only the
 * second proves the guarantee.
 *
 * <p>Completion is a different shape of race and gets its own tests: it is naturally
 * idempotent, so the correct outcome is not a conflict but a no-op that neither
 * re-transitions the document nor publishes a second event.
 */
class DocumentConcurrencyIT extends AbstractDocumentIT {

    @Autowired
    private CaseDocumentRepository documentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("over HTTP: the second editor is told, and does not overwrite")
    void aStaleEditorGetsAConflict() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);
        long versionBothRead = versionOf(documentId);

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Renamed by the first lawyer.pdf", versionBothRead)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Renamed by the second lawyer.pdf", versionBothRead)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/documents/" + documentId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.filename").value("Renamed by the first lawyer.pdf"));
    }

    @Test
    @DisplayName("the losing editor can retry once it has re-read")
    void theConflictIsRecoverable() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);
        long stale = versionOf(documentId);

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("First.pdf", stale)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Second.pdf", versionOf(documentId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filename").value("Second.pdf"));
    }

    @Test
    @DisplayName("in the database: a stale entity written from a second transaction is refused")
    void aStaleEntityCannotOverwriteACommittedChange() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        UUID documentId = UUID.fromString(
                availableDocument(token, matter.caseId(), "Written statement.pdf", 1024));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        CaseDocument stale = transactions.execute(status ->
                documentRepository.findById(documentId).orElseThrow());

        transactions.executeWithoutResult(status -> {
            CaseDocument fresh = documentRepository.findById(documentId).orElseThrow();
            fresh.setDescription("Amended by the lawyer who was at their desk");
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            stale.setDescription("Amended by the lawyer who was at lunch");
            documentRepository.saveAndFlush(stale);
        }))
                .as("without @Version this write would land and the committed edit would vanish")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM documents.case_documents WHERE id = ?",
                String.class, documentId))
                .isEqualTo("Amended by the lawyer who was at their desk");
    }

    @Test
    @DisplayName("a repeated completion cannot corrupt the lifecycle or double-publish")
    void repeatedCompletionIsSafe() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Written statement.pdf", 1024);
        simulateUpload(documentId, 1024, PDF);
        events.clear();

        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
        }

        long completions = events.all().stream()
                .filter(DocumentUploadCompletedEvent.class::isInstance)
                .count();
        assertThat(completions)
                .as("four completion calls, one state change, one event")
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM casework.case_events
                 WHERE case_id = ?::uuid AND event_type = 'DOCUMENT_UPLOADED'
                """, Long.class, matter.caseId()))
                .as("and one timeline entry, not four")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the version the API reports is the one the database is holding")
    void theReportedVersionTracksTheRow() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);

        long before = versionOf(documentId);
        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("Renamed.pdf", before)))
                .andExpect(status().isOk());

        assertThat(versionOf(documentId)).isGreaterThan(before);
    }

    private String edit(String filename, long version) {
        return """
                {"filename":"%s","description":"Amended","version":%d}
                """.formatted(filename, version);
    }
}
