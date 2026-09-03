package com.juriscore.casemanagement;

import com.juriscore.casework.AbstractCaseworkIT;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scaffolding for the Phase 3 integration tests.
 *
 * <p>Builds on the casework fixture rather than copying it: a firm, its staff and its
 * matters are created exactly as Phase 2 creates them, through the real API, so a Phase 3
 * test can never pass against a state the application cannot produce.
 */
abstract class AbstractCaseManagementIT extends AbstractCaseworkIT {

    protected static final Instant NEXT_WEEK = Instant.now().plus(7, ChronoUnit.DAYS);

    /** A firm with one client and one matter — the starting point for most of these tests. */
    protected record Matter(Firm firm, String clientId, String caseId) {
    }

    protected Matter openMatter(String firmName, String email) throws Exception {
        Firm firm = registerFirm(firmName, email);
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");
        return new Matter(firm, clientId, caseId);
    }

    // -------------------------------------------------------------------------- courts

    protected String courtBody(String name, Long version) {
        return """
                {
                  "name": "%s",
                  "courtType": "DISTRICT",
                  "addressLine1": "Fort",
                  "city": "Mumbai",
                  "state": "Maharashtra",
                  "country": "India",
                  "timezone": "Asia/Kolkata"%s
                }
                """.formatted(name, version == null ? "" : ",\n  \"version\": " + version);
    }

    protected String createCourt(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/courts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody(name, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    // ------------------------------------------------------------------------ hearings

    protected String hearingBody(String caseId, String courtId, Instant when) {
        return """
                {"caseId":"%s","courtId":"%s","hearingType":"MENTION","scheduledAt":"%s",
                 "durationMinutes":30,"judgeName":"Justice Rao","courtroom":"Court 4",
                 "purpose":"First listing"}
                """.formatted(caseId, courtId, when);
    }

    protected String scheduleHearing(String token, String caseId, String courtId, Instant when)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/hearings")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hearingBody(caseId, courtId, when)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    protected void moveHearing(String token, String hearingId, String status, int expected)
            throws Exception {
        mockMvc.perform(patch("/api/v1/hearings/" + hearingId + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(status)))
                .andExpect(status().is(expected));
    }

    // --------------------------------------------------------------------------- tasks

    protected String taskBody(String title, UUID assignee) {
        return """
                {"title":"%s","description":"Two pages","priority":"HIGH"%s}
                """.formatted(title,
                assignee == null ? "" : ",\"assignedToUserId\":\"" + assignee + "\"");
    }

    protected String createTask(String token, String caseId, String title, UUID assignee)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cases/" + caseId + "/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody(title, assignee)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    protected void moveTask(String token, String taskId, String status, int expected)
            throws Exception {
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(status)))
                .andExpect(status().is(expected));
    }

    // ----------------------------------------------------------------------- deadlines

    protected String deadlineBody(String title, Instant dueAt, Long version) {
        return """
                {"title":"%s","description":"Within 30 days","deadlineType":"COURT",
                 "dueAt":"%s","source":"Order dated 1 September"%s}
                """.formatted(title, dueAt, version == null ? "" : ",\"version\":" + version);
    }

    protected String createDeadline(String token, String caseId, String title, Instant dueAt)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cases/" + caseId + "/deadlines")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deadlineBody(title, dueAt, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    // ----------------------------------------------------------------------- reminders

    protected String reminderBody(Instant when) {
        return """
                {"remindAt":"%s","channel":"IN_APP","note":"Chase the draft"}
                """.formatted(when);
    }

    protected String remindOnTask(String token, String taskId, Instant when) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/reminders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reminderBody(when)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    // ------------------------------------------------------------------------ plumbing

    /** The current version of any Phase 3 row, read straight from the column. */
    protected long versionOf(String table, String id) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM case_management." + table + " WHERE id = ?::uuid",
                Long.class, id);
        return version == null ? -1 : version;
    }
}
