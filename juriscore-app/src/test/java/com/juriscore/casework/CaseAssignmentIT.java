package com.juriscore.casework;

import com.juriscore.casework.event.CaseLawyerAssignedEvent;
import com.juriscore.casework.event.CaseLawyerUnassignedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staffing a matter, and the lead invariant.
 *
 * <p>The lead assertions read the database rather than the response, because the
 * property being defended — exactly one lead per staffed matter — is a property of the
 * table, not of any single response.
 */
class CaseAssignmentIT extends AbstractCaseworkIT {

    private String caseFor(Firm firm) throws Exception {
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        return createCase(firm.adminToken(), clientId, "Menon v. Iyer");
    }

    @Test
    void assignsAnActiveLawyerOfTheSameFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        String caseId = caseFor(firm);
        events.clear();

        assign(firm.adminToken(), caseId, ravi, null, 201);

        mockMvc.perform(get("/api/v1/cases/" + caseId + "/assignments")
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lawyerUserId").value(ravi.toString()))
                .andExpect(jsonPath("$.data[0].lead").value(true));

        assertThat(events.require(CaseLawyerAssignedEvent.class).eventType())
                .isEqualTo("case.lawyer_assigned");
    }

    @Test
    @DisplayName("a lawyer at another firm can never be staffed, and answers not-found")
    void refusesALawyerFromAnotherFirm() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        inviteAndActivate(theirs, "outsider@kulkarni-legal.test", "LAWYER");
        UUID outsider = userIdOf("outsider@kulkarni-legal.test");
        String caseId = caseFor(mine);

        assign(mine.adminToken(), caseId, outsider, null, 404);

        assertThat(assignmentCount(caseId)).isZero();
    }

    @Test
    @DisplayName("only a LAWYER may be staffed — not a clerk, an administrator or a client")
    void refusesEveryOtherRole() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "clerk@sharma-legal.test", "CLERK");
        inviteAndActivate(firm, "admin2@sharma-legal.test", "FIRM_ADMIN");
        inviteAndActivate(firm, "client@sharma-legal.test", "CLIENT");
        String caseId = caseFor(firm);

        assign(firm.adminToken(), caseId, userIdOf("clerk@sharma-legal.test"), null, 400);
        assign(firm.adminToken(), caseId, userIdOf("admin2@sharma-legal.test"), null, 400);
        assign(firm.adminToken(), caseId, userIdOf("client@sharma-legal.test"), null, 400);

        assertThat(assignmentCount(caseId)).isZero();
    }

    @Test
    void refusesASuspendedLawyer() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        String caseId = caseFor(firm);
        suspend(firm, "ravi@sharma-legal.test");

        assign(firm.adminToken(), caseId, ravi, null, 400);

        assertThat(assignmentCount(caseId)).isZero();
    }

    @Test
    @DisplayName("an invited lawyer who has not yet set a password cannot be staffed")
    void refusesAnInvitedButInactiveLawyer() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        events.clear();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/users/invite")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"pending@sharma-legal.test","firstName":"Pending",
                                 "lastName":"Lawyer","role":"LAWYER"}
                                """))
                .andExpect(status().isCreated());
        String caseId = caseFor(firm);

        assign(firm.adminToken(), caseId, userIdOf("pending@sharma-legal.test"), null, 400);
    }

    @Test
    void refusesADuplicateAssignment() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        String caseId = caseFor(firm);

        assign(firm.adminToken(), caseId, ravi, null, 201);
        assign(firm.adminToken(), caseId, ravi, null, 409);

        assertThat(assignmentCount(caseId)).isEqualTo(1);
    }

    @Test
    @DisplayName("several lawyers may work one matter, and exactly one of them leads")
    void staffsSeveralLawyersWithASingleLead() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        inviteAndActivate(firm, "nita@sharma-legal.test", "LAWYER");
        inviteAndActivate(firm, "vikram@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        UUID nita = userIdOf("nita@sharma-legal.test");
        UUID vikram = userIdOf("vikram@sharma-legal.test");
        String caseId = caseFor(firm);

        assign(firm.adminToken(), caseId, ravi, null, 201);
        assign(firm.adminToken(), caseId, nita, null, 201);
        assign(firm.adminToken(), caseId, vikram, null, 201);

        assertThat(assignmentCount(caseId)).isEqualTo(3);
        assertThat(leadCount(caseId)).isEqualTo(1);
        assertThat(isLead(caseId, ravi)).as("the first assignee leads until told otherwise").isTrue();
    }

    @Test
    void movingTheLeadDemotesTheSittingOne() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        inviteAndActivate(firm, "nita@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        UUID nita = userIdOf("nita@sharma-legal.test");
        String caseId = caseFor(firm);

        assign(firm.adminToken(), caseId, ravi, null, 201);
        assign(firm.adminToken(), caseId, nita, true, 201);

        assertThat(leadCount(caseId)).isEqualTo(1);
        assertThat(isLead(caseId, nita)).isTrue();
        assertThat(isLead(caseId, ravi)).isFalse();
    }

    // ----------------------------------------------------------------- unassignment

    @Test
    void unassignsALawyerWhoIsNotLead() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        inviteAndActivate(firm, "nita@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        UUID nita = userIdOf("nita@sharma-legal.test");
        String caseId = caseFor(firm);
        assign(firm.adminToken(), caseId, ravi, null, 201);
        assign(firm.adminToken(), caseId, nita, null, 201);
        events.clear();

        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/assignments/" + nita)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        assertThat(assignmentCount(caseId)).isEqualTo(1);
        assertThat(leadCount(caseId)).isEqualTo(1);
        assertThat(events.require(CaseLawyerUnassignedEvent.class).eventType())
                .isEqualTo("case.lawyer_unassigned");
    }

    @Test
    @DisplayName("the final lead cannot be removed — a staffed matter is never left leaderless")
    void cannotRemoveTheFinalLead() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        String caseId = caseFor(firm);
        assign(firm.adminToken(), caseId, ravi, null, 201);

        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/assignments/" + ravi)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        assertThat(assignmentCount(caseId)).isEqualTo(1);
        assertThat(leadCount(caseId)).isEqualTo(1);
    }

    @Test
    @DisplayName("removing the lead works when a successor is named, and the lead moves with it")
    void removingTheLeadRequiresAndAppliesAPromotion() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        inviteAndActivate(firm, "nita@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        UUID nita = userIdOf("nita@sharma-legal.test");
        String caseId = caseFor(firm);
        assign(firm.adminToken(), caseId, ravi, null, 201);
        assign(firm.adminToken(), caseId, nita, null, 201);

        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/assignments/" + ravi)
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/assignments/" + ravi)
                        .param("newLeadUserId", nita.toString())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk());

        assertThat(assignmentCount(caseId)).isEqualTo(1);
        assertThat(leadCount(caseId)).isEqualTo(1);
        assertThat(isLead(caseId, nita)).isTrue();
    }

    @Test
    void refusesToPromoteSomebodyWhoIsNotOnTheMatter() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        inviteAndActivate(firm, "nita@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        UUID nita = userIdOf("nita@sharma-legal.test");
        String caseId = caseFor(firm);
        assign(firm.adminToken(), caseId, ravi, null, 201);

        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/assignments/" + ravi)
                        .param("newLeadUserId", nita.toString())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        assertThat(assignmentCount(caseId)).isEqualTo(1);
    }

    @Test
    void unassigningSomebodyWhoWasNeverStaffedIsNotFound() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String caseId = caseFor(firm);

        mockMvc.perform(delete("/api/v1/cases/" + caseId + "/assignments/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("assignments on another firm's matter are not found, not forbidden")
    void cannotStaffAForeignMatter() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        inviteAndActivate(mine, "ravi@sharma-legal.test", "LAWYER");
        UUID myLawyer = userIdOf("ravi@sharma-legal.test");
        String theirCase = createCase(theirs.adminToken(),
                createClient(theirs.adminToken(), "Theirs", "theirs@client.test"), "Their matter");

        assign(mine.adminToken(), theirCase, myLawyer, null, 404);

        assertThat(assignmentCount(theirCase)).isZero();
    }

    private long assignmentCount(String caseId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM casework.case_assignments WHERE case_id = ?::uuid",
                Long.class, caseId);
        return count == null ? 0 : count;
    }
}
