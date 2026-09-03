package com.juriscore.casework;

import com.juriscore.casework.event.CaseCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opening matters, reading them back, filtering them, and editing them. */
class CaseIT extends AbstractCaseworkIT {

    @Test
    void opensAMatterInTheOpenState() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Menon v. Iyer"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.clientId").value(clientId))
                .andExpect(jsonPath("$.data.openedAt").exists());

        // Against the column, not the payload: a null field is serialised as present, so
        // jsonPath().doesNotExist() here would pass for the wrong reason.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT closed_at IS NULL FROM casework.cases WHERE id = ?::uuid",
                Boolean.class, caseId)).isTrue();
        assertThat(events.require(CaseCreatedEvent.class).eventType()).isEqualTo("case.created");
    }

    @Test
    @DisplayName("case numbers run from one, per firm and per year")
    void issuesSequentialCaseNumbersPerFirm() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String myClient = createClient(mine.adminToken(), "Asha Menon", "asha@menon.test");
        String theirClient = createClient(theirs.adminToken(), "Other Client", "other@client.test");

        int year = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear();

        assertThat(numberOf(createCase(mine.adminToken(), myClient, "First")))
                .isEqualTo("CASE-%d-000001".formatted(year));
        assertThat(numberOf(createCase(mine.adminToken(), myClient, "Second")))
                .isEqualTo("CASE-%d-000002".formatted(year));
        assertThat(numberOf(createCase(theirs.adminToken(), theirClient, "Theirs")))
                .as("each firm counts on its own; a shared counter would leak how busy a rival is")
                .isEqualTo("CASE-%d-000001".formatted(year));
    }

    @Test
    void refusesAMatterWithNoClient() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Menon v. Iyer\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refusesAMatterWithNoTitle() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"clientId\":\"%s\"}".formatted(clientId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refusesAMatterForAClientThatDoesNotExist() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Menon v. Iyer\",\"clientId\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("another firm's client answers the same not-found as one that never existed")
    void refusesAMatterForAnotherFirmsClient() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirClient = createClient(theirs.adminToken(), "Their Client", "their@client.test");

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(mine.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Menon v. Iyer\",\"clientId\":\"%s\"}".formatted(theirClient)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a rejected creation burns no case number")
    void aFailedCreationDoesNotConsumeANumber() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        int year = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear();

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Doomed\",\"clientId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        assertThat(numberOf(createCase(firm.adminToken(), clientId, "First real matter")))
                .as("the client is checked before a number is drawn, so nothing was spent")
                .isEqualTo("CASE-%d-000001".formatted(year));
    }

    // ------------------------------------------------------------------------ listing

    @Test
    void listsOnlyTheCallersOwnMatters() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        createCase(mine.adminToken(), createClient(mine.adminToken(), "Mine", "mine@client.test"), "Mine");
        createCase(theirs.adminToken(),
                createClient(theirs.adminToken(), "Theirs", "theirs@client.test"), "Theirs");

        mockMvc.perform(get("/api/v1/cases").header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Mine"));
    }

    @Test
    void filtersByStatusAndByClient() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String first = createClient(firm.adminToken(), "First Client", "first@client.test");
        String second = createClient(firm.adminToken(), "Second Client", "second@client.test");
        String held = createCase(firm.adminToken(), first, "On hold matter");
        createCase(firm.adminToken(), second, "Open matter");

        changeStatus(firm.adminToken(), held, "ON_HOLD", 200);

        mockMvc.perform(get("/api/v1/cases").param("status", "ON_HOLD")
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("On hold matter"));

        mockMvc.perform(get("/api/v1/cases").param("clientId", second)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Open matter"));

        mockMvc.perform(get("/api/v1/cases").param("status", "OPEN").param("clientId", first)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void searchesTitleAndCaseNumberWithinTheFirmOnly() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        createCase(mine.adminToken(), createClient(mine.adminToken(), "Mine", "mine@client.test"),
                "Tenancy at Marine Drive");
        createCase(theirs.adminToken(),
                createClient(theirs.adminToken(), "Theirs", "theirs@client.test"), "Tenancy elsewhere");

        mockMvc.perform(get("/api/v1/cases").param("search", "tenancy")
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }

    // ------------------------------------------------------------------------ editing

    @Test
    void editsAMattersDetails() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Menon v. Iyer (amended)","description":"Amended particulars",
                                 "clientId":"%s","version":%d}
                                """.formatted(clientId, versionOf(caseId, firm.adminToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Menon v. Iyer (amended)"))
                .andExpect(jsonPath("$.data.description").value("Amended particulars"));
    }

    @Test
    @DisplayName("an edit cannot move a matter into another firm's client")
    void editCannotPointAMatterAtAForeignClient() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String myClient = createClient(mine.adminToken(), "Mine", "mine@client.test");
        String theirClient = createClient(theirs.adminToken(), "Theirs", "theirs@client.test");
        String caseId = createCase(mine.adminToken(), myClient, "Menon v. Iyer");

        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(mine.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Menon v. Iyer","clientId":"%s","version":%d}
                                """.formatted(theirClient, versionOf(caseId, mine.adminToken()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));
    }

    // --------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another firm's matter is not found, on every verb")
    void aForeignCaseIsNotFound() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirClient = createClient(theirs.adminToken(), "Theirs", "theirs@client.test");
        String theirCase = createCase(theirs.adminToken(), theirClient, "Their matter");

        mockMvc.perform(get("/api/v1/cases/" + theirCase)
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/cases/" + theirCase)
                        .header("Authorization", bearer(mine.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\",\"clientId\":\"%s\",\"version\":0}"
                                .formatted(theirClient)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));

        changeStatus(mine.adminToken(), theirCase, "IN_PROGRESS", 404);

        mockMvc.perform(get("/api/v1/cases/" + theirCase + "/timeline")
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/cases/" + theirCase + "/assignments")
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a matter that never existed answers exactly as one belonging to somebody else")
    void anUnknownCaseIsIndistinguishableFromAForeignOne() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/cases/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------------ helpers

    private String numberOf(String caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT case_number FROM casework.cases WHERE id = ?::uuid", String.class, caseId);
    }

    private long versionOf(String caseId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("data").path("version").asLong();
    }
}
