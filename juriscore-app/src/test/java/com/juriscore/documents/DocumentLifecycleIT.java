package com.juriscore.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.juriscore.documents.event.DocumentCreatedEvent;
import com.juriscore.documents.event.DocumentDeletedEvent;
import com.juriscore.documents.event.DocumentDownloadRequestedEvent;
import com.juriscore.documents.event.DocumentUpdatedEvent;
import com.juriscore.documents.event.DocumentUploadCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole document flow: register, upload, complete, download, rename, remove. */
class DocumentLifecycleIT extends AbstractDocumentIT {

    @Test
    @DisplayName("registering returns metadata and a link, and the document is not yet usable")
    void registeringIssuesAnUploadLink() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();

        MvcResult result = mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("Written statement.pdf", PDF, 1024)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.document.status").value("UPLOADING"))
                .andExpect(jsonPath("$.data.document.filename").value("Written statement.pdf"))
                .andExpect(jsonPath("$.data.document.contentType").value(PDF))
                .andExpect(jsonPath("$.data.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.uploadMethod").value("PUT"))
                .andExpect(jsonPath("$.data.requiredContentType").value(PDF))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andReturn();

        assertThat(json(result).path("data").path("expiresInSeconds").asLong()).isPositive();
        assertThat(events.require(DocumentCreatedEvent.class).eventType())
                .isEqualTo("document.created");
    }

    @Test
    @DisplayName("the storage key is never returned — the bucket layout is internal")
    void theApiNeverExposesTheStorageKey() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();

        MvcResult created = mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("Written statement.pdf", PDF, 1024)))
                .andExpect(status().isCreated())
                .andReturn();
        String documentId = json(created).path("data").path("document").path("id").asText();
        String body = created.getResponse().getContentAsString();

        assertThat(body).doesNotContain("storageKey").doesNotContain("organizations/");

        MvcResult fetched = mockMvc.perform(get("/api/v1/documents/" + documentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(fetched.getResponse().getContentAsString())
                .doesNotContain("storageKey").doesNotContain("organizations/");
    }

    @Test
    @DisplayName("the key is built from ids, so a hostile filename cannot reach the object path")
    void aHostileFilenameNeverReachesTheKey() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();

        // A traversal attempt is refused outright...
        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/documents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBody("../../etc/passwd", PDF, 1024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // ...and even an accepted name contributes nothing to the key.
        String documentId = register(token, matter.caseId(), "Odd name (final) v2.pdf", 1024);
        assertThat(storageKeyOf(documentId))
                .isEqualTo("organizations/" + matter.firm().id()
                        + "/cases/" + matter.caseId() + "/documents/" + documentId)
                .doesNotContain("Odd name");
    }

    // ----------------------------------------------------------------------- completion

    @Test
    void completingConfirmsAgainstStorageAndMakesTheDocumentUsable() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Written statement.pdf", 1024);
        simulateUpload(documentId, 2048, PDF);
        events.clear();

        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.uploadedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.fileSize")
                        .value(2048));

        assertThat(events.require(DocumentUploadCompletedEvent.class).getSize()).isEqualTo(2048);
        assertThat(timelineTypes(matter)).contains("DOCUMENT_UPLOADED");
    }

    @Test
    @DisplayName("completing with nothing uploaded is refused — the client's word is not evidence")
    void completingWithoutAnObjectIsRefused() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Written statement.pdf", 1024);

        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        assertThat(statusOf(documentId)).isEqualTo("UPLOADING");
    }

    @Test
    @DisplayName("an object bigger than the ceiling is rejected and the document fails")
    void anOversizedObjectIsCaughtAtCompletion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        // Declared small enough to pass the first check, then uploaded far larger — the
        // exact gap a presigned PUT cannot close on its own.
        String documentId = register(token, matter.caseId(), "Huge.pdf", 1024);
        simulateUpload(documentId, 500L * 1024 * 1024, PDF);

        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(statusOf(documentId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("completing twice is a no-op: no second event, no restamped upload time")
    void completingTwiceIsIdempotent() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);

        MvcResult first = mockMvc.perform(get("/api/v1/documents/" + documentId)
                        .header("Authorization", bearer(token))).andReturn();
        String uploadedAt = json(first).path("data").path("uploadedAt").asText();
        events.clear();

        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.uploadedAt").value(uploadedAt));

        assertThat(events.latest(DocumentUploadCompletedEvent.class))
                .as("a retried completion must not publish again")
                .isEmpty();
        assertThat(timelineTypes(matter).stream().filter("DOCUMENT_UPLOADED"::equals).count())
                .as("nor append a second timeline entry")
                .isEqualTo(1);
    }

    @Test
    void aFailedDocumentCannotBeCompleted() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Huge.pdf", 1024);
        simulateUpload(documentId, 500L * 1024 * 1024, PDF);
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        simulateUpload(documentId, 1024, PDF);
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    // ------------------------------------------------------------------------ downloads

    @Test
    void downloadingReturnsALinkForAnAvailableDocument() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);
        events.clear();

        mockMvc.perform(get("/api/v1/documents/" + documentId + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.filename").value("Written statement.pdf"))
                .andExpect(jsonPath("$.data.contentType").value(PDF))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        assertThat(events.require(DocumentDownloadRequestedEvent.class).getActorUserId())
                .isEqualTo(userIdOf("asha@sharma-legal.test"));
    }

    @Test
    @DisplayName("an unconfirmed document has no file, so it gets no link")
    void downloadingAnUploadingDocumentIsRefused() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Written statement.pdf", 1024);

        mockMvc.perform(get("/api/v1/documents/" + documentId + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    void downloadingADeletedDocumentIsNotFound() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);
        mockMvc.perform(delete("/api/v1/documents/" + documentId)
                        .header("Authorization", bearer(token))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/" + documentId + "/download")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    // -------------------------------------------------------------- rename and removal

    @Test
    void renamesADocumentAndRefusesAStaleVersion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);
        long stale = versionOf(documentId);
        events.clear();

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("Rejoinder.pdf", stale)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filename").value("Rejoinder.pdf"))
                .andExpect(jsonPath("$.data.description").value("Amended"));

        assertThat(events.require(DocumentUpdatedEvent.class).eventType())
                .isEqualTo("document.updated");

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("Losing edit.pdf", stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/documents/" + documentId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.filename").value("Rejoinder.pdf"));
    }

    @Test
    @DisplayName("an edit cannot change the file's type, size, status or matter")
    void updateCannotMutateTheStoredObjectsProperties() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);
        String key = storageKeyOf(documentId);

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"Renamed.pdf","description":null,"version":%d,
                                 "contentType":"application/x-sh","fileSize":999999999,
                                 "status":"DELETED","caseId":"%s","storageKey":"organizations/evil"}
                                """.formatted(versionOf(documentId), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value(PDF))
                .andExpect(jsonPath("$.data.fileSize").value(1024))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.caseId").value(matter.caseId()));

        assertThat(storageKeyOf(documentId)).isEqualTo(key);
    }

    @Test
    void renameIsValidatedTheSameWayAsUpload() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);

        mockMvc.perform(put("/api/v1/documents/" + documentId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("../escape.pdf", versionOf(documentId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("deletion soft-deletes the row and removes the object after the commit")
    void deletionIsSoftAndRemovesTheObject() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);
        String key = storageKeyOf(documentId);
        assertThat(storage.contains(key)).isTrue();
        events.clear();

        mockMvc.perform(delete("/api/v1/documents/" + documentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents.case_documents WHERE id = ?::uuid",
                Long.class, documentId))
                .as("the metadata row stays so the matter's timeline keeps resolving")
                .isEqualTo(1L);
        assertThat(storage.contains(key))
                .as("the object is removed after commit, by DeletedDocumentObjectCleaner")
                .isFalse();
        assertThat(events.require(DocumentDeletedEvent.class).eventType())
                .isEqualTo("document.deleted");
        assertThat(timelineTypes(matter)).contains("DOCUMENT_DELETED");
    }

    @Test
    void deletingTwiceIsNotFound() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = availableDocument(token, matter.caseId(), "Written statement.pdf", 1024);

        mockMvc.perform(delete("/api/v1/documents/" + documentId)
                        .header("Authorization", bearer(token))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/documents/" + documentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("an unconfirmed upload can be abandoned without pretending it succeeded")
    void anUploadingDocumentCanBeDeleted() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String documentId = register(token, matter.caseId(), "Abandoned.pdf", 1024);

        mockMvc.perform(delete("/api/v1/documents/" + documentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));
    }

    // ------------------------------------------------------------------------ helpers

    private String editBody(String filename, long version) {
        return """
                {"filename":"%s","description":"Amended","version":%d}
                """.formatted(filename, version);
    }

    private List<String> timelineTypes(Matter matter) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/timeline")
                        .param("size", "50")
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isOk())
                .andReturn();
        List<String> types = new ArrayList<>();
        for (JsonNode item : json(result).path("data").path("items")) {
            types.add(item.path("eventType").asText());
        }
        return types;
    }
}
