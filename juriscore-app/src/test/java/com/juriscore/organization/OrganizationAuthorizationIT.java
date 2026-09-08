package com.juriscore.organization;

import com.juriscore.casework.AbstractCaseworkIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read the firm's own profile, and where the tenant comes from.
 *
 * <p>{@code GET /organizations/current} previously carried no {@code @PreAuthorize} at all.
 * It was never anonymous — the chain requires authentication for anything unmatched — but
 * "not anonymous" was the only rule it had, so every authenticated role including CLIENT
 * could read the firm's registration number, billing address and contact details. The
 * endpoint now states its audience, and this class is that statement in executable form.
 *
 * <p>The second half is about the tenant. The organization is taken from the access token
 * and from nowhere else; these tests try the obvious ways a caller might name a different
 * one and assert that none of them changes the answer.
 */
class OrganizationAuthorizationIT extends AbstractCaseworkIT {

    private static final String CURRENT = "/api/v1/organizations/current";

    @Test
    @DisplayName("an anonymous caller is refused")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get(CURRENT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a bearer token that is not a token is refused")
    void garbageTokenIsRefused() throws Exception {
        mockMvc.perform(get(CURRENT).header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a firm administrator can read their own firm")
    void firmAdminIsAllowed() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get(CURRENT).header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Sharma & Associates"));
    }

    @Test
    @DisplayName("a lawyer can read their own firm")
    void lawyerIsAllowed() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String token = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");

        mockMvc.perform(get(CURRENT).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Sharma & Associates"));
    }

    @Test
    @DisplayName("a clerk can read their own firm")
    void clerkIsAllowed() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String token = inviteAndActivate(firm, "clerk@sharma-legal.test", "CLERK");

        mockMvc.perform(get(CURRENT).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a client of the firm is refused: they are an external party, not staff")
    void clientIsRefused() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String token = inviteAndActivate(firm, "client@rao.test", "CLIENT");

        // A CLIENT sees what has been shared with them. The firm's registration number and
        // billing address were never meant to be on that list.
        mockMvc.perform(get(CURRENT).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a platform administrator is refused, because they belong to no firm")
    void superAdminIsRefused() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String token = platformAdminToken("asha@sharma-legal.test");

        // Not an oversight: "the caller's own firm" has no answer for an unscoped caller,
        // and a SUPER_ADMIN reads any firm through GET /organizations/{id}, which names one.
        mockMvc.perform(get(CURRENT).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the tenant comes from the token, not from anything the caller can send")
    void tenantCannotBeSpoofed() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Rao & Company", "vikram@rao-legal.test");
        assertThat(theirs.id()).isNotEqualTo(mine.id());

        // Every shape a caller might reach for: a query parameter, and the headers a
        // tenant-aware application might otherwise be tempted to read.
        mockMvc.perform(get(CURRENT)
                        .header("Authorization", bearer(mine.adminToken()))
                        .param("organizationId", theirs.id())
                        .header("X-Organization-Id", theirs.id())
                        .header("X-Tenant-Id", theirs.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Sharma & Associates"));
    }

    @Test
    @DisplayName("no response leaks the tenant identifier back to the caller")
    void theResponseDoesNotEchoTheTenantId() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        // Echoing internal tenant ids gives a caller something to start substituting into
        // other requests; the convention across this API is that responses omit them.
        mockMvc.perform(get(CURRENT).header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationId").doesNotExist());
    }

    @Test
    @DisplayName("reading another firm by id requires a platform administrator")
    void anotherFirmByIdRequiresSuperAdmin() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Rao & Company", "vikram@rao-legal.test");

        mockMvc.perform(get("/api/v1/organizations/" + theirs.id())
                        .header("Authorization", bearer(mine.adminToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("updating the firm profile stays with firm administrators")
    void updateStaysAdminOnly() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String lawyerToken = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");

        // Widening read access to staff must not have widened write access with it.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(CURRENT)
                        .header("Authorization", bearer(lawyerToken))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed by a lawyer\"}"))
                .andExpect(status().isForbidden());
    }
}
