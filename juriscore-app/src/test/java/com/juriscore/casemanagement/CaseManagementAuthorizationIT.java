package com.juriscore.casemanagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 3 role matrix, one assertion per cell.
 *
 * <p>Every capability is exercised by every role that has it and every role that does
 * not, because an authorization table is only as good as its negative half.
 *
 * <p>Two refusals appear in this system and they mean different things. A role that may
 * not perform an action gets 403: the endpoint exists, the caller is not allowed. A
 * caller reaching across firms gets 404: the resource must not be confirmed to exist.
 * This class covers the first; the per-resource classes cover the second.
 *
 * <p>The positive assertion is "not 403" rather than "200" — whether a well-formed
 * request then succeeds belongs to the other classes, and conflating the two would make
 * every authorization cell depend on unrelated business state.
 */
class CaseManagementAuthorizationIT extends AbstractCaseManagementIT {

    private static final Instant SOON = Instant.now().plus(2, ChronoUnit.DAYS);

    private Matter matter;
    private String lawyerToken;
    private String clerkToken;
    private String clientRoleToken;
    private String platformToken;
    private String courtId;
    private String hearingId;
    private String taskId;
    private String deadlineId;
    private String reminderId;

    @BeforeEach
    void staffTheFirm() throws Exception {
        matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        lawyerToken = inviteAndActivate(matter.firm(), "ravi@sharma-legal.test", "LAWYER");
        clerkToken = inviteAndActivate(matter.firm(), "clerk@sharma-legal.test", "CLERK");
        clientRoleToken = inviteAndActivate(matter.firm(), "portal@sharma-legal.test", "CLIENT");
        platformToken = platformAdminToken("asha@sharma-legal.test");

        String admin = matter.firm().adminToken();
        courtId = createCourt(admin, "City Civil Court");
        hearingId = scheduleHearing(admin, matter.caseId(), courtId, NEXT_WEEK);
        taskId = createTask(admin, matter.caseId(), "Draft", null);
        deadlineId = createDeadline(admin, matter.caseId(), "File",
                Instant.now().plus(30, ChronoUnit.DAYS));
        reminderId = remindOnTask(admin, taskId, SOON);
    }

    // -------------------------------------------------------------------------- courts

    @Test
    void readingCourtsIsOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/courts"), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/courts"), clientRoleToken, platformToken);

        allow(() -> get("/api/v1/courts/" + courtId), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/courts/" + courtId), clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("courts are reference data: administrators and clerks maintain the list")
    void maintainingCourtsIsForAdministratorsAndClerks() throws Exception {
        allow(() -> body(post("/api/v1/courts"), courtBody("One", null)), admin());
        allow(() -> body(post("/api/v1/courts"), courtBody("Two", null)), clerkToken);
        deny(() -> body(post("/api/v1/courts"), courtBody("Three", null)),
                lawyerToken, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> edit =
                () -> body(put("/api/v1/courts/" + courtId), courtBody("Renamed", 0L));
        allow(edit, admin(), clerkToken);
        deny(edit, lawyerToken, clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("only an administrator retires a court")
    void retiringACourtIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> retire = () -> delete("/api/v1/courts/" + courtId);

        deny(retire, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(retire, admin());
    }

    // ------------------------------------------------------------------------ hearings

    @Test
    void readingAndSchedulingHearingsIsOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/hearings"), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/hearings"), clientRoleToken, platformToken);

        allow(() -> get("/api/v1/hearings/" + hearingId), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/hearings/" + hearingId), clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> schedule = () -> body(post("/api/v1/hearings"),
                hearingBody(matter.caseId(), courtId, NEXT_WEEK));
        allow(schedule, admin(), lawyerToken, clerkToken);
        deny(schedule, clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("a clerk maintains a listing, but does not record what the bench decided")
    void recordingAnOutcomeIsForAdministratorsAndLawyers() throws Exception {
        Supplier<MockHttpServletRequestBuilder> move = () ->
                body(patch("/api/v1/hearings/" + hearingId + "/status"),
                        "{\"status\":\"ADJOURNED\"}");

        deny(move, clerkToken, clientRoleToken, platformToken);
        allow(move, admin());
    }

    @Test
    void editingAHearingIsOpenToAllStaff() throws Exception {
        Supplier<MockHttpServletRequestBuilder> edit = () -> body(put("/api/v1/hearings/" + hearingId),
                """
                {"courtId":"%s","hearingType":"EVIDENCE","scheduledAt":"%s","version":0}
                """.formatted(courtId, NEXT_WEEK));

        allow(edit, admin(), lawyerToken, clerkToken);
        deny(edit, clientRoleToken, platformToken);
    }

    // --------------------------------------------------------------------------- tasks

    @Test
    void everythingButRemovingATaskIsOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/cases/" + matter.caseId() + "/tasks"),
                admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/cases/" + matter.caseId() + "/tasks"),
                clientRoleToken, platformToken);

        allow(() -> get("/api/v1/tasks/" + taskId), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/tasks/" + taskId), clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> create = () ->
                body(post("/api/v1/cases/" + matter.caseId() + "/tasks"), taskBody("New", null));
        allow(create, admin(), lawyerToken, clerkToken);
        deny(create, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> edit = () -> body(put("/api/v1/tasks/" + taskId),
                "{\"title\":\"Edited\",\"priority\":\"LOW\",\"version\":0}");
        allow(edit, admin(), lawyerToken, clerkToken);
        deny(edit, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> move = () ->
                body(patch("/api/v1/tasks/" + taskId + "/status"), "{\"status\":\"IN_PROGRESS\"}");
        allow(move, admin(), lawyerToken, clerkToken);
        deny(move, clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("removing a task is an administrator's, matching the one destructive verb in casework")
    void removingATaskIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> remove = () -> delete("/api/v1/tasks/" + taskId);

        deny(remove, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(remove, admin());
    }

    // ----------------------------------------------------------------------- deadlines

    @Test
    void everythingButRemovingADeadlineIsOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/cases/" + matter.caseId() + "/deadlines"),
                admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/cases/" + matter.caseId() + "/deadlines"),
                clientRoleToken, platformToken);

        allow(() -> get("/api/v1/deadlines/" + deadlineId), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/deadlines/" + deadlineId), clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> create = () ->
                body(post("/api/v1/cases/" + matter.caseId() + "/deadlines"),
                        deadlineBody("Another", NEXT_WEEK, null));
        allow(create, admin(), lawyerToken, clerkToken);
        deny(create, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> move = () ->
                body(patch("/api/v1/deadlines/" + deadlineId + "/status"),
                        "{\"status\":\"COMPLETED\"}");
        allow(move, admin(), lawyerToken, clerkToken);
        deny(move, clientRoleToken, platformToken);
    }

    @Test
    void removingADeadlineIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> remove =
                () -> delete("/api/v1/deadlines/" + deadlineId);

        deny(remove, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(remove, admin());
    }

    // ----------------------------------------------------------------------- reminders

    @Test
    @DisplayName("setting a reminder for yourself is not an administrative act")
    void remindersAreOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/reminders"), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/reminders"), clientRoleToken, platformToken);

        allow(() -> get("/api/v1/reminders/" + reminderId), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/reminders/" + reminderId), clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> onTask = () ->
                body(post("/api/v1/tasks/" + taskId + "/reminders"), reminderBody(SOON));
        allow(onTask, admin(), lawyerToken, clerkToken);
        deny(onTask, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> onDeadline = () ->
                body(post("/api/v1/deadlines/" + deadlineId + "/reminders"), reminderBody(SOON));
        allow(onDeadline, admin(), lawyerToken, clerkToken);
        deny(onDeadline, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> cancel =
                () -> delete("/api/v1/reminders/" + reminderId);
        deny(cancel, clientRoleToken, platformToken);
        allow(cancel, admin(), lawyerToken, clerkToken);
    }

    // --------------------------------------------------------------- anonymous access

    @Test
    @DisplayName("nothing in case management is reachable without a token")
    void everyEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/courts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/courts/" + courtId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/hearings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/hearings/" + hearingId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tasks/" + taskId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/tasks"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/deadlines/" + deadlineId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reminders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reminders/" + reminderId)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------ helpers

    private String admin() {
        return matter.firm().adminToken();
    }

    /**
     * Each token gets a freshly built request. Reusing one builder would stack a second
     * {@code Authorization} header onto the first rather than replacing it, and the test
     * would then be asserting something nobody intended.
     */
    private void allow(Supplier<MockHttpServletRequestBuilder> request, String... tokens)
            throws Exception {
        for (String token : tokens) {
            mockMvc.perform(request.get().header("Authorization", bearer(token)))
                    .andExpect(status().is(not(403)));
        }
    }

    private void deny(Supplier<MockHttpServletRequestBuilder> request, String... tokens)
            throws Exception {
        for (String token : tokens) {
            mockMvc.perform(request.get().header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
