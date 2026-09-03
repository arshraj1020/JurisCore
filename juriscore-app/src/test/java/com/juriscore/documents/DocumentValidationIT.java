package com.juriscore.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validation over HTTP, and the two properties that matter most: a refused request writes
 * no row, and it never reaches storage.
 */
class DocumentValidationIT extends AbstractDocumentIT {

    @ParameterizedTest(name = "refuses the filename {0}")
    @ValueSource(strings = {"../../etc/passwd", "folder/reply.pdf", "folder\\\\reply.pdf", ".."})
    void refusesAPathLikeFilename(String filename) throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody(filename, PDF, 1024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertNothingWasWritten();
    }

    @Test
    void refusesAMissingFilename() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"application/pdf\",\"fileSize\":1024}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertNothingWasWritten();
    }

    @ParameterizedTest(name = "refuses the content type {0}")
    @ValueSource(strings = {
            "application/x-sh", "text/html", "image/svg+xml", "application/x-msdownload",
            "application/octet-stream"
    })
    @DisplayName("an allowlist, so script and executable types never get a link")
    void refusesADisallowedContentType(String contentType) throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("harmless.pdf", contentType, 1024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("the extension is a claim, not evidence: a .pdf name with a refused type is refused")
    void theExtensionIsNotTrusted() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("looks-fine.pdf", "application/x-sh", 1024)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesZeroAndNegativeSizes() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("empty.pdf", PDF, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("negative.pdf", PDF, -1)))
                .andExpect(status().isBadRequest());

        assertNothingWasWritten();
    }

    @Test
    void refusesAFileOverTheConfiguredMaximum() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("huge.pdf", PDF, 500L * 1024 * 1024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertNothingWasWritten();
    }

    @Test
    @DisplayName("a name with a newline cannot reach the Content-Disposition header")
    void refusesControlCharactersInAFilename() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"reply.pdf\\r\\nX-Evil: 1\","
                                + "\"contentType\":\"application/pdf\",\"fileSize\":1024}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("ordinary human filenames are accepted, unicode and all")
    void acceptsRealisticFilenames() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();

        register(token, matter.caseId(), "Menon v. Iyer — written statement (final).pdf", 1024);
        register(token, matter.caseId(), "वकालतनामा.pdf", 2048);
    }

    @Test
    void aDocumentForACaseThatDoesNotExistIsNotFound() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + UUID.randomUUID() + "/documents")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("Written statement.pdf", PDF, 1024)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    @Test
    void listsAreNewestFirstAndPageStably() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        for (int i = 1; i <= 5; i++) {
            register(token, matter.caseId(), "Filing " + i + ".pdf", 1024);
        }

        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/documents")
                        .param("size", "2").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(5))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    void filtersByStatus() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        register(token, matter.caseId(), "Pending.pdf", 1024);
        availableDocument(token, matter.caseId(), "Done.pdf", 1024);

        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/documents")
                        .param("status", "AVAILABLE").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].filename").value("Done.pdf"));

        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/documents")
                        .param("status", "UPLOADING").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].filename").value("Pending.pdf"));
    }

    /** A refused request must leave neither a metadata row nor anything in the bucket. */
    private void assertNothingWasWritten() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents.case_documents", Long.class)).isZero();
        assertThat(storage.size())
                .as("a rejected request must never have reached storage")
                .isZero();
    }
}
