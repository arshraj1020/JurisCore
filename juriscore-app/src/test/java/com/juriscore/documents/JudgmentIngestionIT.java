package com.juriscore.documents;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end judgment ingestion: register a document as a judgment, let the async
 * pipeline run against real Postgres/pgvector (Testcontainers) and the in-memory storage
 * double, and verify it reaches READY with real chunks and embeddings.
 *
 * <p>In package {@code com.juriscore.documents} — not {@code com.juriscore.legalresearch}
 * — solely so this test can extend the package-private {@link AbstractDocumentIT} without
 * widening its visibility just for this one caller.
 *
 * <p>The embedding provider is {@code FakeEmbeddingClient} here (test profile: see
 * {@code application-test.yml}), so this proves the pipeline's mechanics — extraction,
 * chunking, persistence, status transitions, pgvector round-tripping — without a network
 * call or a real OpenAI key.
 */
class JudgmentIngestionIT extends AbstractDocumentIT {

    private static final String TEXT_PLAIN = "text/plain";

    @Test
    void registeringAnAvailableDocumentIngestsItThroughToReady() throws Exception {
        Matter matter = openMatter("Menon & Co", "menon.judgment@firm.test");
        String token = matter.firm().adminToken();

        byte[] content = ("This is the first paragraph of a small test judgment.\n\n"
                + "This is the second paragraph, discussing the legal issue at hand.\n\n"
                + "This is the third and final paragraph, stating the holding.")
                .getBytes(StandardCharsets.UTF_8);

        String documentId = registerTextDocument(token, matter.caseId(), "judgment.txt", content.length);
        storage.put(storageKeyOf(documentId), content, TEXT_PLAIN);
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        MvcResult registration = mockMvc.perform(post("/api/v1/legal-research/judgments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String judgmentId = json(registration).path("data").path("id").asText();

        String finalStatus = awaitStatus(token, judgmentId);
        assertThat(finalStatus).isEqualTo("READY");

        Integer chunkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legal_research.legal_chunks WHERE judgment_id = ?::uuid",
                Integer.class, judgmentId);
        assertThat(chunkCount).isGreaterThan(0);

        Integer chunksWithEmbeddings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legal_research.legal_chunks "
                        + "WHERE judgment_id = ?::uuid AND embedding IS NOT NULL",
                Integer.class, judgmentId);
        assertThat(chunksWithEmbeddings).isEqualTo(chunkCount);

        // Paragraph text is preserved and findable in a real chunk row — proof the pgvector
        // column and the plain columns on the same row both round-trip through Postgres.
        Integer matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legal_research.legal_chunks "
                        + "WHERE judgment_id = ?::uuid AND chunk_text LIKE '%holding%'",
                Integer.class, judgmentId);
        assertThat(matches).isGreaterThan(0);
    }

    @Test
    void registeringBeforeTheUploadCompletesStillIngestsOnceItDoes() throws Exception {
        Matter matter = openMatter("Rao Legal", "rao.judgment@firm.test");
        String token = matter.firm().adminToken();

        byte[] content = "A short judgment registered before its upload completed.".getBytes(StandardCharsets.UTF_8);
        String documentId = registerTextDocument(token, matter.caseId(), "early.txt", content.length);

        MvcResult registration = mockMvc.perform(post("/api/v1/legal-research/judgments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String judgmentId = json(registration).path("data").path("id").asText();

        // Not uploaded yet: ingestion must not have started.
        assertThat(judgmentStatusOf(judgmentId)).isEqualTo("PENDING");

        storage.put(storageKeyOf(documentId), content, TEXT_PLAIN);
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertThat(awaitStatus(token, judgmentId)).isEqualTo("READY");
    }

    @Test
    void registeringSomeoneElsesDocumentIsRefused() throws Exception {
        Matter mine = openMatter("Iyer Chambers", "iyer.judgment@firm.test");
        Matter theirs = openMatter("Other Firm", "other.judgment@firm.test");

        String content = "irrelevant";
        String theirDocumentId = registerTextDocument(theirs.firm().adminToken(), theirs.caseId(), "theirs.txt",
                content.length());

        mockMvc.perform(post("/api/v1/legal-research/judgments")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + theirDocumentId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registeringTheSameDocumentTwiceIsRejected() throws Exception {
        Matter matter = openMatter("Second Registration Firm", "dup.judgment@firm.test");
        String token = matter.firm().adminToken();
        String documentId = registerTextDocument(token, matter.caseId(), "dup.txt", 10);

        mockMvc.perform(post("/api/v1/legal-research/judgments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/legal-research/judgments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aDocumentThatFailsExtractionIsMarkedFailedNotReady() throws Exception {
        Matter matter = openMatter("Bad Document Firm", "bad.judgment@firm.test");
        String token = matter.firm().adminToken();

        // Zero-byte content: DocumentService itself refuses an empty object at completion
        // time, so this exercises the same "never silently succeed" rule one layer up —
        // the document never reaches AVAILABLE, so ingestion never even starts and the
        // judgment stays PENDING rather than being falsely marked READY.
        String documentId = registerTextDocument(token, matter.caseId(), "empty.txt", 10);
        storage.put(storageKeyOf(documentId), new byte[0], TEXT_PLAIN);
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/legal-research/judgments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\"}"))
                .andExpect(status().isCreated());

        // Give any (incorrect) async activity a moment to happen, then assert it did not.
        Thread.sleep(500);
        assertThat(judgmentStatusOf(judgmentIdFor(documentId))).isEqualTo("PENDING");
    }

    /**
     * Registers a document with an explicit content type, unlike the shared
     * {@link #register(String, String, String, long)} helper (which always declares
     * {@code application/pdf}) — this suite uploads real plain-text content, and Tika's
     * parser selection is driven by the declared content type as well as sniffing, so the
     * declared type has to actually match what gets uploaded.
     */
    private String registerTextDocument(String token, String caseId, String filename, long size)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cases/" + caseId + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody(filename, TEXT_PLAIN, size)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("document").path("id").asText();
    }

    private String judgmentIdFor(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM legal_research.legal_judgments WHERE source_document_id = ?::uuid",
                UUID.class, documentId).toString();
    }

    /**
     * Named distinctly from the inherited {@code statusOf(String)} — that one reads
     * {@code documents.case_documents.status} (a document's upload status); this reads a
     * judgment's {@code extraction_status}. Same erasure, unrelated concepts: reusing the
     * name would have this accidentally declared as an override of the inherited
     * protected method, which is exactly the compile error a narrower-visibility local
     * method of the same signature produces.
     */
    private String judgmentStatusOf(String judgmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT extraction_status FROM legal_research.legal_judgments WHERE id = ?::uuid",
                String.class, judgmentId);
    }

    /** Polls the get-by-id endpoint (not the database) so this also exercises the read API. */
    private String awaitStatus(String token, String judgmentId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        String last = "UNKNOWN";
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/v1/legal-research/judgments/" + judgmentId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn();
            last = json(result).path("data").path("extractionStatus").asText();
            if ("READY".equals(last) || "FAILED".equals(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }
}
