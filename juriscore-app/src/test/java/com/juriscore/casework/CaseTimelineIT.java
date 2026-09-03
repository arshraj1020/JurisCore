package com.juriscore.casework;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the timeline records, in what order, and what it refuses to let anybody undo. */
class CaseTimelineIT extends AbstractCaseworkIT {

    @Test
    @DisplayName("opening a matter writes the first entry without anybody asking")
    void openingAMatterIsRecorded() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        mockMvc.perform(get("/api/v1/cases/" + caseId + "/timeline")
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].eventType").value("CASE_CREATED"))
                .andExpect(jsonPath("$.data.items[0].actorUserId").value(
                        userIdOf("asha@sharma-legal.test").toString()));
    }

    @Test
    @DisplayName("every meaningful action lands on the timeline, newest first")
    void recordsAssignmentsUnassignmentsAndStatusChanges() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        inviteAndActivate(firm, "nita@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        UUID nita = userIdOf("nita@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        assign(firm.adminToken(), caseId, ravi, null, 201);
        assign(firm.adminToken(), caseId, nita, null, 201);
        changeStatus(firm.adminToken(), caseId, "IN_PROGRESS", 200);
        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/assignments/" + nita)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        assertThat(eventTypes(caseId, firm.adminToken()))
                .containsExactly("LAWYER_UNASSIGNED", "CASE_STATUS_CHANGED",
                        "LAWYER_ASSIGNED", "LAWYER_ASSIGNED", "CASE_CREATED");
    }

    @Test
    void addsAManualNote() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        mockMvc.perform(post("/api/v1/cases/" + caseId + "/timeline")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Client confirmed the tenancy dates by phone.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventType").value("MANUAL_NOTE"))
                .andExpect(jsonPath("$.data.summary")
                        .value("Client confirmed the tenancy dates by phone."));

        assertThat(eventTypes(caseId, firm.adminToken()))
                .containsExactly("MANUAL_NOTE", "CASE_CREATED");
    }

    @Test
    void rejectsAnEmptyNote() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        mockMvc.perform(post("/api/v1/cases/" + caseId + "/timeline")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("there is no way to edit or remove an entry — the verbs are simply not there")
    void theTimelineIsAppendOnlyOverHttp() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        MvcResult listed = mockMvc.perform(get("/api/v1/cases/" + caseId + "/timeline")
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andReturn();
        String entryId = json(listed).path("data").path("items").get(0).path("id").asText();

        mockMvc.perform(put("/api/v1/cases/" + caseId + "/timeline/" + entryId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Rewritten history\"}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/timeline/" + entryId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(patch("/api/v1/cases/" + caseId + "/timeline/" + entryId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Rewritten history\"}"))
                .andExpect(status().is4xxClientError());

        assertThat(eventTypes(caseId, firm.adminToken())).containsExactly("CASE_CREATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT summary FROM casework.case_events WHERE id = ?::uuid", String.class, entryId))
                .contains("opened");
    }

    @Test
    @DisplayName("paging is stable even when entries share a timestamp")
    void pagesWithoutRepeatingOrSkippingEntries() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        for (int i = 1; i <= 6; i++) {
            mockMvc.perform(post("/api/v1/cases/" + caseId + "/timeline")
                            .header("Authorization", bearer(firm.adminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"summary\":\"Note %d\"}".formatted(i)))
                    .andExpect(status().isCreated());
        }

        List<String> firstPage = idsOnPage(caseId, firm.adminToken(), 0, 3);
        List<String> secondPage = idsOnPage(caseId, firm.adminToken(), 1, 3);
        List<String> thirdPage = idsOnPage(caseId, firm.adminToken(), 2, 3);

        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).hasSize(3);
        assertThat(thirdPage).hasSize(1);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);

        List<String> everything = new ArrayList<>(firstPage);
        everything.addAll(secondPage);
        everything.addAll(thirdPage);
        assertThat(everything)
                .as("seven entries, each seen exactly once across the three pages")
                .hasSize(7)
                .doesNotHaveDuplicates();
    }

    @Test
    void aForeignMattersTimelineIsNotFound() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirCase = createCase(theirs.adminToken(),
                createClient(theirs.adminToken(), "Theirs", "theirs@client.test"), "Their matter");

        mockMvc.perform(get("/api/v1/cases/" + theirCase + "/timeline")
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/cases/" + theirCase + "/timeline")
                        .header("Authorization", bearer(mine.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Injected note\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));

        Long entries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM casework.case_events WHERE case_id = ?::uuid",
                Long.class, theirCase);
        assertThat(entries).as("only the opening entry their own firm wrote").isEqualTo(1L);
    }

    @Test
    @DisplayName("a rolled-back action leaves no entry behind")
    void aRefusedActionWritesNoEntry() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");
        changeStatus(firm.adminToken(), caseId, "CLOSED", 200);

        changeStatus(firm.adminToken(), caseId, "IN_PROGRESS", 409);

        assertThat(eventTypes(caseId, firm.adminToken()))
                .containsExactly("CASE_STATUS_CHANGED", "CASE_CREATED");
    }

    // ------------------------------------------------------------------------ helpers

    private List<String> eventTypes(String caseId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cases/" + caseId + "/timeline")
                        .param("size", "50")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> types = new ArrayList<>();
        for (JsonNode item : json(result).path("data").path("items")) {
            types.add(item.path("eventType").asText());
        }
        return types;
    }

    private List<String> idsOnPage(String caseId, String token, int page, int size) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cases/" + caseId + "/timeline")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> ids = new ArrayList<>();
        for (JsonNode item : json(result).path("data").path("items")) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }
}
