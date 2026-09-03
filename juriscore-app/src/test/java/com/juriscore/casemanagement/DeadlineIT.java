package com.juriscore.casemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.juriscore.casemanagement.event.DeadlineCompletedEvent;
import com.juriscore.casemanagement.event.DeadlineCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** Dates matters have to meet. */
class DeadlineIT extends AbstractCaseManagementIT {

    private static final Instant DUE = Instant.now().plus(30, ChronoUnit.DAYS);

    @Test
    void recordsADeadlineInTheOpenState() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        String id = createDeadline(matter.firm().adminToken(), matter.caseId(),
                "File the written statement", DUE);

        mockMvc.perform(get("/api/v1/deadlines/" + id)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("File the written statement"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.deadlineType").value("COURT"))
                .andExpect(jsonPath("$.data.source").value("Order dated 1 September"));

        assertThat(events.require(DeadlineCreatedEvent.class).eventType())
                .isEqualTo("deadline.created");
        assertThat(timelineTypes(matter)).containsExactly("DEADLINE_CREATED", "CASE_CREATED");
    }

    @Test
    @DisplayName("a deadline in the past is allowed — firms record dates they have already missed")
    void aPastDueDateIsAccepted() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        createDeadline(matter.firm().adminToken(), matter.caseId(), "Missed filing",
                Instant.now().minus(3, ChronoUnit.DAYS));
    }

    @Test
    void rejectsADeadlineWithNoTitleTypeOrDate() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/deadlines")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \",\"deadlineType\":\"COURT\",\"dueAt\":\"%s\"}"
                                .formatted(DUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/deadlines")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"File\",\"dueAt\":\"%s\"}".formatted(DUE)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/deadlines")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"File\",\"deadlineType\":\"COURT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesADeadlineOnAnotherFirmsMatter() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + theirs.caseId() + "/deadlines")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deadlineBody("Injected", DUE, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------------ lifecycle

    @Test
    void meetingADeadlineStampsItAndReachesTheTimeline() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String id = createDeadline(matter.firm().adminToken(), matter.caseId(), "File", DUE);
        events.clear();

        move(matter.firm().adminToken(), id, "COMPLETED", 200);

        mockMvc.perform(get("/api/v1/deadlines/" + id)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").exists());

        assertThat(events.require(DeadlineCompletedEvent.class).eventType())
                .isEqualTo("deadline.completed");
        assertThat(timelineTypes(matter))
                .containsExactly("DEADLINE_COMPLETED", "DEADLINE_CREATED", "CASE_CREATED");
    }

    @Test
    void withdrawingIsRecordedButIsNotACompletion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String id = createDeadline(matter.firm().adminToken(), matter.caseId(), "File", DUE);
        events.clear();

        move(matter.firm().adminToken(), id, "CANCELLED", 200);

        assertThat(events.latest(DeadlineCompletedEvent.class)).isEmpty();
        assertThat(timelineTypes(matter))
                .containsExactly("DEADLINE_CANCELLED", "DEADLINE_CREATED", "CASE_CREATED");
    }

    @Test
    void bothEndingsAreTerminal() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String met = createDeadline(token, matter.caseId(), "Met", DUE);
        String dropped = createDeadline(token, matter.caseId(), "Dropped", DUE);

        move(token, met, "COMPLETED", 200);
        move(token, met, "OPEN", 409);
        move(token, met, "CANCELLED", 409);

        move(token, dropped, "CANCELLED", 200);
        move(token, dropped, "OPEN", 409);
        move(token, dropped, "COMPLETED", 409);
    }

    // ------------------------------------------------------------------ editing, removal

    @Test
    void editsADeadlineAndRefusesAStaleVersion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String id = createDeadline(token, matter.caseId(), "File", DUE);
        long stale = versionOf("deadlines", id);

        mockMvc.perform(put("/api/v1/deadlines/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deadlineBody("File the rejoinder", DUE, stale)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("File the rejoinder"));

        mockMvc.perform(put("/api/v1/deadlines/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deadlineBody("Losing edit", DUE, stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void removalIsSoftAndHidesItFromTheList() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String kept = createDeadline(token, matter.caseId(), "Kept", DUE);
        String removed = createDeadline(token, matter.caseId(), "Removed", DUE);

        mockMvc.perform(delete("/api/v1/deadlines/" + removed).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/deadlines")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(kept));

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.deadlines WHERE id = ?::uuid",
                Long.class, removed);
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    void filtersByStatus() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String open = createDeadline(token, matter.caseId(), "Open one", DUE);
        String met = createDeadline(token, matter.caseId(), "Met one", DUE);
        move(token, met, "COMPLETED", 200);

        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/deadlines")
                        .param("status", "OPEN").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(open));
    }

    // ---------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another firm's deadline is not found, on every verb")
    void aForeignDeadlineIsNotFound() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirDeadline = createDeadline(theirs.firm().adminToken(), theirs.caseId(),
                "Theirs", DUE);
        String token = mine.firm().adminToken();

        mockMvc.perform(get("/api/v1/deadlines/" + theirDeadline)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        move(token, theirDeadline, "COMPLETED", 404);

        mockMvc.perform(put("/api/v1/deadlines/" + theirDeadline)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deadlineBody("Hijacked", DUE, 0L)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/deadlines/" + theirDeadline)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownDeadlineIsIndistinguishableFromAForeignOne() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/deadlines/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------------ helpers

    private void move(String token, String id, String status, int expected) throws Exception {
        mockMvc.perform(patch("/api/v1/deadlines/" + id + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(status)))
                .andExpect(status().is(expected));
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
