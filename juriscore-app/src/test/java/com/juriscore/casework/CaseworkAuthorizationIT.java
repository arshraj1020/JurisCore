package com.juriscore.casework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role matrix, one assertion per cell.
 *
 * <p>Every capability is exercised by every role that has it and every role that does
 * not, because an authorization table is only as good as its negative half — and because
 * a rule that lives in an annotation is exactly the kind that gets widened by accident.
 *
 * <p>Two refusals appear in this system and they mean different things. A role that may
 * not perform an action gets 403 {@code ACCESS_DENIED}: the endpoint exists, the caller
 * is not allowed. A caller reaching across firms gets 404: the resource must not be
 * confirmed to exist at all. This class covers the first; {@code CaseIT} and
 * {@code ClientIT} cover the second.
 *
 * <p>The positive assertion is "not 403" rather than "200". Whether a well-formed request
 * then succeeds is what the other classes are for; conflating the two here would make
 * every authorization cell depend on unrelated business state.
 */
class CaseworkAuthorizationIT extends AbstractCaseworkIT {

    private Firm firm;
    private String lawyerToken;
    private String clerkToken;
    private String clientRoleToken;
    private String platformToken;
    private String clientId;
    private String caseId;
    private UUID lawyerUserId;

    @BeforeEach
    void staffTheFirm() throws Exception {
        firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        lawyerToken = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");
        clerkToken = inviteAndActivate(firm, "clerk@sharma-legal.test", "CLERK");
        clientRoleToken = inviteAndActivate(firm, "portal@sharma-legal.test", "CLIENT");
        platformToken = platformAdminToken("asha@sharma-legal.test");
        lawyerUserId = userIdOf("ravi@sharma-legal.test");

        clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");
    }

    // --------------------------------------------------------------------- clients

    @Test
    void readingClientsIsOpenToAllStaffAndClosedToEverybodyElse() throws Exception {
        allow(() -> get("/api/v1/clients"), firm.adminToken(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/clients"), clientRoleToken, platformToken);

        allow(() -> get("/api/v1/clients/" + clientId), firm.adminToken(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/clients/" + clientId), clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("a lawyer maintains matters, not the client book")
    void creatingClientsIsForAdministratorsAndClerks() throws Exception {
        allow(() -> body(post("/api/v1/clients"), clientBody("One", "one@client.test")),
                firm.adminToken());
        allow(() -> body(post("/api/v1/clients"), clientBody("Two", "two@client.test")),
                clerkToken);
        deny(() -> body(post("/api/v1/clients"), clientBody("Three", "three@client.test")),
                lawyerToken, clientRoleToken, platformToken);
    }

    @Test
    void editingClientsIsForAdministratorsAndClerks() throws Exception {
        Supplier<MockHttpServletRequestBuilder> edit =
                () -> body(put("/api/v1/clients/" + clientId), clientBody("Renamed", "asha@menon.test"));

        allow(edit, firm.adminToken(), clerkToken);
        deny(edit, lawyerToken, clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("only a firm administrator removes a client")
    void deletingClientsIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> remove = () -> delete("/api/v1/clients/" + clientId);

        deny(remove, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(remove, firm.adminToken());
    }

    // ----------------------------------------------------------------------- cases

    @Test
    @DisplayName("cases are firm-wide: an unassigned lawyer reads them like everybody else")
    void readingCasesIsOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/cases"), firm.adminToken(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/cases"), clientRoleToken, platformToken);

        allow(() -> get("/api/v1/cases/" + caseId), firm.adminToken(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/cases/" + caseId), clientRoleToken, platformToken);
    }

    @Test
    void openingMattersIsOpenToAllStaff() throws Exception {
        Supplier<MockHttpServletRequestBuilder> open = () -> body(post("/api/v1/cases"),
                "{\"title\":\"New matter\",\"clientId\":\"%s\"}".formatted(clientId));

        allow(open, firm.adminToken(), lawyerToken, clerkToken);
        deny(open, clientRoleToken, platformToken);
    }

    @Test
    void editingMattersIsOpenToAllStaff() throws Exception {
        Supplier<MockHttpServletRequestBuilder> edit = () -> body(put("/api/v1/cases/" + caseId),
                "{\"title\":\"Edited\",\"clientId\":\"%s\",\"version\":0}".formatted(clientId));

        allow(edit, firm.adminToken(), lawyerToken, clerkToken);
        deny(edit, clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("a clerk maintains case data but does not move a matter through its lifecycle")
    void changingStatusIsForAdministratorsAndLawyers() throws Exception {
        Supplier<MockHttpServletRequestBuilder> move = () ->
                body(patch("/api/v1/cases/" + caseId + "/status"), "{\"status\":\"IN_PROGRESS\"}");

        deny(move, clerkToken, clientRoleToken, platformToken);
        allow(move, firm.adminToken(), lawyerToken);
    }

    // ------------------------------------------------------------------ assignments

    @Test
    @DisplayName("staffing is an administrator's decision — a lawyer cannot assign a colleague")
    void assigningLawyersIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> staff = () ->
                body(post("/api/v1/cases/" + caseId + "/assignments"),
                        "{\"lawyerUserId\":\"%s\"}".formatted(lawyerUserId));

        deny(staff, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(staff, firm.adminToken());
    }

    @Test
    void unassigningIsForAdministratorsOnly() throws Exception {
        assign(firm.adminToken(), caseId, lawyerUserId, null, 201);

        deny(() -> delete("/api/v1/cases/" + caseId + "/assignments/" + lawyerUserId),
                lawyerToken, clerkToken, clientRoleToken, platformToken);
    }

    @Test
    void readingAssignmentsIsOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/cases/" + caseId + "/assignments"),
                firm.adminToken(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/cases/" + caseId + "/assignments"), clientRoleToken, platformToken);
    }

    // --------------------------------------------------------------------- timeline

    @Test
    void readingTheTimelineIsOpenToAllStaff() throws Exception {
        allow(() -> get("/api/v1/cases/" + caseId + "/timeline"),
                firm.adminToken(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/cases/" + caseId + "/timeline"), clientRoleToken, platformToken);
    }

    @Test
    void annotatingTheTimelineIsOpenToAllStaff() throws Exception {
        Supplier<MockHttpServletRequestBuilder> note = () ->
                body(post("/api/v1/cases/" + caseId + "/timeline"),
                        "{\"summary\":\"Spoke to the client.\"}");

        allow(note, firm.adminToken(), lawyerToken, clerkToken);
        deny(note, clientRoleToken, platformToken);
    }

    // --------------------------------------------------------------- anonymous access

    @Test
    @DisplayName("nothing in casework is reachable without a token")
    void everyCaseworkEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/clients")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/clients/" + clientId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/cases")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/cases/" + caseId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/cases/" + caseId + "/timeline"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/cases/" + caseId + "/assignments"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------ helpers

    /**
     * Each token gets a freshly built request. Reusing one builder across tokens would
     * stack a second {@code Authorization} header onto the first rather than replacing
     * it, and the test would then be asserting something nobody intended.
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
