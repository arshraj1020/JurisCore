package com.juriscore.casework;

import com.juriscore.casework.event.CaseStatusChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lifecycle over HTTP and against the database's own check constraints.
 *
 * <p>{@code CaseStatusPolicyTest} already covers the matrix in isolation. What this adds
 * is that the rule survives the round trip: the endpoint returns 409, the row is
 * unchanged, and {@code ck_cases_closed_at} agrees with whatever the service wrote.
 */
class CaseLifecycleIT extends AbstractCaseworkIT {

    private String openAMatter(Firm firm) throws Exception {
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        return createCase(firm.adminToken(), clientId, "Menon v. Iyer");
    }

    @ParameterizedTest(name = "OPEN -> {0} is accepted")
    @CsvSource({"IN_PROGRESS", "ON_HOLD", "CLOSED"})
    void everyMoveOutOfOpenIsAccepted(String target) throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);

        changeStatus(firm.adminToken(), caseId, target, 200);

        mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(jsonPath("$.data.status").value(target));
    }

    @Test
    void walksTheWholeLifecycle() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);

        changeStatus(firm.adminToken(), caseId, "IN_PROGRESS", 200);
        changeStatus(firm.adminToken(), caseId, "ON_HOLD", 200);
        changeStatus(firm.adminToken(), caseId, "IN_PROGRESS", 200);
        changeStatus(firm.adminToken(), caseId, "CLOSED", 200);

        mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedAt").exists());
    }

    @ParameterizedTest(name = "a matter in {0} refuses to go to {1}")
    @CsvSource({
            "IN_PROGRESS, OPEN",
            "ON_HOLD,     OPEN",
            "OPEN,        OPEN",
            "IN_PROGRESS, IN_PROGRESS"
    })
    void refusesAnIllegalMoveWithAConflict(String reach, String target) throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);
        if (!"OPEN".equals(reach)) {
            if ("ON_HOLD".equals(reach)) {
                changeStatus(firm.adminToken(), caseId, "ON_HOLD", 200);
            } else {
                changeStatus(firm.adminToken(), caseId, reach, 200);
            }
        }

        mockMvc.perform(patch("/api/v1/cases/" + caseId + "/status")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(target)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        mockMvc.perform(get("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(jsonPath("$.data.status").value(reach));
    }

    @ParameterizedTest(name = "a closed matter cannot go to {0}")
    @CsvSource({"OPEN", "IN_PROGRESS", "ON_HOLD", "CLOSED"})
    @DisplayName("CLOSED is terminal — a matter is not reopened in Phase 2")
    void closedIsTerminal(String target) throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);
        changeStatus(firm.adminToken(), caseId, "CLOSED", 200);

        mockMvc.perform(patch("/api/v1/cases/" + caseId + "/status")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(target)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        assertThat(closedAtIsSet(caseId))
                .as("the refused reopen must not have cleared the closing time")
                .isTrue();
    }

    @Test
    @DisplayName("closing stamps closed_at; nothing else ever does")
    void onlyClosingStampsTheClosingTime() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);

        changeStatus(firm.adminToken(), caseId, "IN_PROGRESS", 200);
        assertThat(closedAtIsSet(caseId)).isFalse();

        changeStatus(firm.adminToken(), caseId, "CLOSED", 200);
        assertThat(closedAtIsSet(caseId)).isTrue();
    }

    @Test
    @DisplayName("an ordinary edit cannot change status, even if the body carries one")
    void putCannotMoveTheLifecycle() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");

        // The DTO has no status field, so an extra one is simply not bound. The point of
        // the test is that it does not become a back door either.
        mockMvc.perform(put("/api/v1/cases/" + caseId)
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Menon v. Iyer","clientId":"%s","version":0,"status":"CLOSED"}
                                """.formatted(clientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        assertThat(closedAtIsSet(caseId)).isFalse();
    }

    @Test
    void aStatusChangePublishesTheEvent() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);
        events.clear();

        changeStatus(firm.adminToken(), caseId, "IN_PROGRESS", 200);

        CaseStatusChangedEvent event = events.require(CaseStatusChangedEvent.class);
        assertThat(event.eventType()).isEqualTo("case.status_changed");
        assertThat(event.getPreviousStatus().name()).isEqualTo("OPEN");
        assertThat(event.getNewStatus().name()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("a refused transition publishes nothing")
    void aRefusedTransitionNotifiesNobody() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);
        changeStatus(firm.adminToken(), caseId, "CLOSED", 200);
        events.clear();

        changeStatus(firm.adminToken(), caseId, "IN_PROGRESS", 409);

        assertThat(events.latest(CaseStatusChangedEvent.class)).isEmpty();
    }

    @Test
    void rejectsAStatusThatIsNotOneOfTheFour() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = openAMatter(firm);

        mockMvc.perform(patch("/api/v1/cases/" + caseId + "/status")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    private boolean closedAtIsSet(String caseId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT closed_at IS NOT NULL FROM casework.cases WHERE id = ?::uuid",
                Boolean.class, caseId));
    }
}
