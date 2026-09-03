package com.juriscore.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract between a document row and the object behind it.
 *
 * <p>Everything here failed silently at least once, and every one of those failures was
 * invisible to a unit test with a mocked repository: a key that was computed correctly in
 * memory and never written, a status that was set on an entity and rolled away by the very
 * exception it was recorded for, and a link that carried the key it was supposed to
 * withhold. So each is asserted against the database or the response body rather than
 * against an object the test itself is holding.
 */
class DocumentStorageContractIT extends AbstractDocumentIT {

    @Test
    @DisplayName("the stamped key is what the database keeps — no placeholder survives the insert")
    void theCanonicalKeyIsWhatIsPersisted() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String documentId = register(matter.firm().adminToken(), matter.caseId(), "Filing.pdf", 1024);

        assertThat(storageKeyOf(documentId))
                .as("the key is derived from the generated id, so it can only be written in a "
                        + "second statement — one that a non-updatable mapping used to discard")
                .isEqualTo("organizations/" + matter.firm().id()
                        + "/cases/" + matter.caseId() + "/documents/" + documentId)
                .doesNotStartWith("pending:")
                .doesNotStartWith("reserved:");
    }

    @Test
    @DisplayName("the upload link is opaque, and still addresses exactly the right object")
    void theUploadLinkResolvesToTheKeyWithoutRevealingIt() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();

        MvcResult created = mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("Filing.pdf", PDF, 1024)))
                .andExpect(status().isCreated())
                .andReturn();

        String documentId = json(created).path("data").path("document").path("id").asText();
        String uploadUrl = json(created).path("data").path("uploadUrl").asText();
        String key = storageKeyOf(documentId);

        assertThat(uploadUrl)
                .as("a link is the one thing a client is handed; it must not spell out the "
                        + "bucket layout the rest of the API withholds")
                .doesNotContain(key)
                .doesNotContain("organizations/")
                .doesNotContain(matter.firm().id())
                .doesNotContain(matter.caseId());
        assertThat(storage.keyForLink(uploadUrl))
                .as("and it must still address the one object it was issued for")
                .contains(key);
    }

    @Test
    void theDownloadLinkIsOpaqueTheSameWay() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Filing.pdf", 1024);

        MvcResult result = mockMvc.perform(get("/api/v1/documents/" + documentId + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();

        String downloadUrl = json(result).path("data").path("downloadUrl").asText();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("organizations/");
        assertThat(storage.keyForLink(downloadUrl)).contains(storageKeyOf(documentId));
    }

    @Test
    @DisplayName("a rejected upload stays rejected: FAILED is committed, not rolled back with "
            + "the error that reported it")
    void theFailedTransitionOutlivesTheErrorResponse() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Huge.pdf", 1024);
        simulateUpload(documentId, 500L * 1024 * 1024, PDF);

        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(statusOf(documentId))
                .as("the response is an error, so its transaction rolls back — the transition "
                        + "has to be committed by DocumentFailureRecorder or it is lost")
                .isEqualTo("FAILED");

        // Terminal for completion, whatever is in the bucket now.
        simulateUpload(documentId, 1024, PDF);
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        // And it never becomes downloadable by any route.
        mockMvc.perform(get("/api/v1/documents/" + documentId + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        assertThat(statusOf(documentId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("an empty object is rejected the same way, and just as durably")
    void anEmptyObjectAlsoFailsForGood() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Empty.pdf", 1024);
        simulateUpload(documentId, 0, PDF);

        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(statusOf(documentId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("two firms registering at the same moment get keys under their own prefixes")
    void concurrentRegistrationsDoNotShareAKey() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        String a = register(mine.firm().adminToken(), mine.caseId(), "A.pdf", 1024);
        String b = register(mine.firm().adminToken(), mine.caseId(), "B.pdf", 1024);
        String c = register(theirs.firm().adminToken(), theirs.caseId(), "C.pdf", 1024);

        assertThat(storageKeyOf(a)).isNotEqualTo(storageKeyOf(b));
        assertThat(storageKeyOf(a)).startsWith("organizations/" + mine.firm().id() + "/");
        assertThat(storageKeyOf(c)).startsWith("organizations/" + theirs.firm().id() + "/");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT storage_key) FROM documents.case_documents", Long.class))
                .as("the reservation each row is inserted with must be unique, or a second "
                        + "registration in the same flush would collide on the unique index")
                .isEqualTo(3L);
    }
}
