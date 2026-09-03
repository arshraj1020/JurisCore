package com.juriscore.casemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.juriscore.casemanagement.event.HearingScheduledEvent;
import com.juriscore.casemanagement.event.HearingStatusChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Listings: scheduling, filtering, editing, the lifecycle, and the case timeline. */
class HearingIT extends AbstractCaseManagementIT {

    @Test
    void listsAMatterBeforeACourt() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");

        String hearingId = scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId,
                NEXT_WEEK);

        mockMvc.perform(get("/api/v1/hearings/" + hearingId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value(matter.caseId()))
                .andExpect(jsonPath("$.data.courtId").value(courtId))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.hearingType").value("MENTION"))
                .andExpect(jsonPath("$.data.durationMinutes").value(30));

        assertThat(events.require(HearingScheduledEvent.class).eventType())
                .isEqualTo("hearing.scheduled");
    }

    @Test
    @DisplayName("scheduling writes a HEARING_SCHEDULED entry on the matter's own timeline")
    void schedulingReachesTheCaseTimeline() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");

        scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId, NEXT_WEEK);

        assertThat(timelineTypes(matter))
                .as("Phase 3 writes to the timeline Phase 2 owns, not to one of its own")
                .containsExactly("HEARING_SCHEDULED", "CASE_CREATED");
    }

    @Test
    @DisplayName("another firm's matter answers not-found, and nothing is written")
    void refusesToListAgainstAForeignCase() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String myCourt = createCourt(mine.firm().adminToken(), "City Civil Court");

        mockMvc.perform(post("/api/v1/hearings")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hearingBody(theirs.caseId(), myCourt, NEXT_WEEK)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));

        assertThat(hearingCount()).isZero();
    }

    @Test
    @DisplayName("another firm's court answers not-found too")
    void refusesToListBeforeAForeignCourt() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirCourt = createCourt(theirs.adminToken(), "Their Court");

        mockMvc.perform(post("/api/v1/hearings")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hearingBody(mine.caseId(), theirCourt, NEXT_WEEK)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(hearingCount()).isZero();
    }

    @Test
    void refusesToListBeforeARetiredCourt() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/courts/" + courtId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/hearings")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hearingBody(matter.caseId(), courtId, NEXT_WEEK)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rejectsAnImpossibleDuration() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");

        mockMvc.perform(post("/api/v1/hearings")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId":"%s","courtId":"%s","hearingType":"MENTION",
                                 "scheduledAt":"%s","durationMinutes":0}
                                """.formatted(matter.caseId(), courtId, NEXT_WEEK)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------------ lifecycle

    @ParameterizedTest(name = "SCHEDULED -> {0} is accepted")
    @CsvSource({"COMPLETED", "ADJOURNED", "CANCELLED"})
    void everyMoveOutOfScheduledIsAccepted(String target) throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");
        String hearingId = scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId,
                NEXT_WEEK);

        moveHearing(matter.firm().adminToken(), hearingId, target, 200);

        mockMvc.perform(get("/api/v1/hearings/" + hearingId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(jsonPath("$.data.status").value(target));
    }

    @Test
    @DisplayName("an adjourned hearing can be relisted — that is what the state is for")
    void adjournedGoesBackToScheduled() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");
        String hearingId = scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId,
                NEXT_WEEK);

        moveHearing(matter.firm().adminToken(), hearingId, "ADJOURNED", 200);
        moveHearing(matter.firm().adminToken(), hearingId, "SCHEDULED", 200);
        moveHearing(matter.firm().adminToken(), hearingId, "COMPLETED", 200);

        assertThat(timelineTypes(matter)).containsExactly(
                "HEARING_COMPLETED", "HEARING_SCHEDULED", "HEARING_ADJOURNED",
                "HEARING_SCHEDULED", "CASE_CREATED");
    }

    @ParameterizedTest(name = "a completed hearing cannot go to {0}")
    @CsvSource({"SCHEDULED", "ADJOURNED", "CANCELLED", "COMPLETED"})
    void completedIsTerminal(String target) throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");
        String hearingId = scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId,
                NEXT_WEEK);
        moveHearing(matter.firm().adminToken(), hearingId, "COMPLETED", 200);

        mockMvc.perform(patch("/api/v1/hearings/" + hearingId + "/status")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(target)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("a refused transition writes no timeline entry and publishes nothing")
    void aRefusedTransitionLeavesNoTrace() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");
        String hearingId = scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId,
                NEXT_WEEK);
        moveHearing(matter.firm().adminToken(), hearingId, "CANCELLED", 200);
        events.clear();

        moveHearing(matter.firm().adminToken(), hearingId, "SCHEDULED", 409);

        assertThat(events.latest(HearingStatusChangedEvent.class)).isEmpty();
        assertThat(timelineTypes(matter))
                .containsExactly("HEARING_CANCELLED", "HEARING_SCHEDULED", "CASE_CREATED");
    }

    @Test
    void recordingAnOutcomeStoresItWithTheMove() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String courtId = createCourt(matter.firm().adminToken(), "City Civil Court");
        String hearingId = scheduleHearing(matter.firm().adminToken(), matter.caseId(), courtId,
                NEXT_WEEK);

        mockMvc.perform(patch("/api/v1/hearings/" + hearingId + "/status")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"outcome\":\"Judgment reserved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("Judgment reserved"));
    }

    // -------------------------------------------------------------------------- listing

    @Test
    void filtersByCaseCourtStatusAndDateRange() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String courtA = createCourt(token, "Court A");
        String courtB = createCourt(token, "Court B");
        String otherCase = createCase(token, matter.clientId(), "Second matter");

        Instant soon = Instant.now().plus(2, ChronoUnit.DAYS);
        Instant later = Instant.now().plus(30, ChronoUnit.DAYS);
        String first = scheduleHearing(token, matter.caseId(), courtA, soon);
        scheduleHearing(token, otherCase, courtB, later);

        mockMvc.perform(get("/api/v1/hearings").param("caseId", matter.caseId())
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first));

        mockMvc.perform(get("/api/v1/hearings").param("courtId", courtB)
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1));

        moveHearing(token, first, "COMPLETED", 200);
        mockMvc.perform(get("/api/v1/hearings").param("status", "COMPLETED")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first));

        mockMvc.perform(get("/api/v1/hearings")
                        .param("from", Instant.now().toString())
                        .param("to", Instant.now().plus(7, ChronoUnit.DAYS).toString())
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems")
                        .value(1));
    }

    @Test
    @DisplayName("a half-open date range is a caller mistake, not an unbounded scan")
    void refusesAnIncompleteDateRange() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/hearings").param("from", Instant.now().toString())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    // -------------------------------------------------------------------------- editing

    @Test
    void editsAHearingAndRefusesAStaleVersion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String courtId = createCourt(token, "City Civil Court");
        String other = createCourt(token, "Sessions Court");
        String hearingId = scheduleHearing(token, matter.caseId(), courtId, NEXT_WEEK);
        long stale = versionOf("hearings", hearingId);

        mockMvc.perform(put("/api/v1/hearings/" + hearingId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody(other, stale)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courtId").value(other))
                .andExpect(jsonPath("$.data.hearingType").value("EVIDENCE"));

        mockMvc.perform(put("/api/v1/hearings/" + hearingId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody(courtId, stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("an edit cannot move a hearing through its lifecycle")
    void putCannotChangeStatus() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String courtId = createCourt(token, "City Civil Court");
        String hearingId = scheduleHearing(token, matter.caseId(), courtId, NEXT_WEEK);

        mockMvc.perform(put("/api/v1/hearings/" + hearingId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courtId":"%s","hearingType":"EVIDENCE","scheduledAt":"%s",
                                 "version":%d,"status":"COMPLETED"}
                                """.formatted(courtId, NEXT_WEEK, versionOf("hearings", hearingId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
    }

    // ---------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another firm's hearing is not found, on every verb")
    void aForeignHearingIsNotFound() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirCourt = createCourt(theirs.firm().adminToken(), "Their Court");
        String theirHearing = scheduleHearing(theirs.firm().adminToken(), theirs.caseId(),
                theirCourt, NEXT_WEEK);

        mockMvc.perform(get("/api/v1/hearings/" + theirHearing)
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("HEARING_NOT_FOUND"));

        moveHearing(mine.firm().adminToken(), theirHearing, "COMPLETED", 404);

        mockMvc.perform(put("/api/v1/hearings/" + theirHearing)
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody(theirCourt, 0L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsOnlyTheCallersOwnHearings() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        scheduleHearing(mine.firm().adminToken(), mine.caseId(),
                createCourt(mine.firm().adminToken(), "Mine"), NEXT_WEEK);
        scheduleHearing(theirs.firm().adminToken(), theirs.caseId(),
                createCourt(theirs.firm().adminToken(), "Theirs"), NEXT_WEEK);

        mockMvc.perform(get("/api/v1/hearings")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    void anUnknownHearingIsIndistinguishableFromAForeignOne() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/hearings/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("HEARING_NOT_FOUND"));
    }

    // ------------------------------------------------------------------------ helpers

    private String editBody(String courtId, long version) {
        return """
                {"courtId":"%s","hearingType":"EVIDENCE","scheduledAt":"%s","durationMinutes":60,
                 "judgeName":"Justice Rao","courtroom":"Court 4","purpose":"Evidence","version":%d}
                """.formatted(courtId, NEXT_WEEK, version);
    }

    private long hearingCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.hearings", Long.class);
        return count == null ? 0 : count;
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
