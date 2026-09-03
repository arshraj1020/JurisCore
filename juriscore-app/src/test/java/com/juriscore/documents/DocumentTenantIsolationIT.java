package com.juriscore.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tenant boundary, on every verb.
 *
 * <p>The answer is always 404, never 403, and that distinction is the point of the whole
 * class: a 403 would confirm the document exists, which turns document ids into an
 * oracle for what another firm holds. Documents are the most sensitive thing in this
 * platform, so this is asserted for reads, writes, downloads and deletes separately
 * rather than once.
 */
class DocumentTenantIsolationIT extends AbstractDocumentIT {

    @Test
    @DisplayName("another firm's document answers not-found on every verb")
    void aForeignDocumentIsNotFoundEverywhere() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirDocument = availableDocument(theirs.firm().adminToken(), theirs.caseId(),
                "Their filing.pdf", 1024);
        String token = mine.firm().adminToken();

        mockMvc.perform(get("/api/v1/documents/" + theirDocument)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/documents/" + theirDocument + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/documents/" + theirDocument + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/documents/" + theirDocument)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"Hijacked.pdf\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/documents/" + theirDocument)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a foreign delete leaves both the metadata and the object untouched")
    void aForeignDeleteChangesNothing() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirDocument = availableDocument(theirs.firm().adminToken(), theirs.caseId(),
                "Their filing.pdf", 1024);
        String theirKey = storageKeyOf(theirDocument);

        mockMvc.perform(delete("/api/v1/documents/" + theirDocument)
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isNotFound());

        assertThat(statusOf(theirDocument)).isEqualTo("AVAILABLE");
        assertThat(storage.contains(theirKey))
                .as("a refused request must not have reached storage at all")
                .isTrue();
    }

    @Test
    void aForeignListReturnsNothing() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        availableDocument(mine.firm().adminToken(), mine.caseId(), "Mine.pdf", 1024);
        availableDocument(theirs.firm().adminToken(), theirs.caseId(), "Theirs.pdf", 1024);

        mockMvc.perform(get("/api/v1/cases/" + mine.caseId() + "/documents")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].filename").value("Mine.pdf"));

        // And the other firm's case is not even reachable to list against.
        mockMvc.perform(get("/api/v1/cases/" + theirs.caseId() + "/documents")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a document cannot be attached to another firm's matter")
    void aForeignCaseCannotReceiveADocument() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + theirs.caseId() + "/documents")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("Injected.pdf", PDF, 1024)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents.case_documents", Long.class))
                .as("no row, and therefore no upload link, for a matter the caller cannot see")
                .isZero();
    }

    @Test
    @DisplayName("an id that never existed answers exactly as one belonging to somebody else")
    void enumerationRevealsNothing() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirDocument = availableDocument(theirs.firm().adminToken(), theirs.caseId(),
                "Their filing.pdf", 1024);
        String token = mine.firm().adminToken();

        var forRandom = mockMvc.perform(get("/api/v1/documents/" + UUID.randomUUID())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andReturn();
        var forTheirs = mockMvc.perform(get("/api/v1/documents/" + theirDocument)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(json(forTheirs).path("error").path("code").asText())
                .as("the two answers must be indistinguishable, or document ids become an "
                        + "oracle for what other firms hold")
                .isEqualTo(json(forRandom).path("error").path("code").asText());
    }

    @Test
    @DisplayName("each firm's objects live under its own prefix, so keys can never collide")
    void objectKeysAreSegregatedByTenant() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String myDocument = register(mine.firm().adminToken(), mine.caseId(), "Mine.pdf", 1024);
        String theirDocument = register(theirs.firm().adminToken(), theirs.caseId(), "Theirs.pdf", 1024);

        assertThat(storageKeyOf(myDocument))
                .startsWith("organizations/" + mine.firm().id() + "/");
        assertThat(storageKeyOf(theirDocument))
                .startsWith("organizations/" + theirs.firm().id() + "/");
        assertThat(organizationOf(myDocument)).hasToString(mine.firm().id());
    }

    @Test
    void aDeletedDocumentIsGoneFromListsAndFromDirectReads() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String kept = availableDocument(token, matter.caseId(), "Kept.pdf", 1024);
        String removed = availableDocument(token, matter.caseId(), "Removed.pdf", 1024);

        mockMvc.perform(delete("/api/v1/documents/" + removed).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(kept));

        mockMvc.perform(get("/api/v1/documents/" + removed).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }
}
