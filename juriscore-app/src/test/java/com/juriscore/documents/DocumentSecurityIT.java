package com.juriscore.documents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.function.Supplier;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The document role matrix, one assertion per cell.
 *
 * <p>Two roles are asserted absent from every capability and that is the substance of the
 * class. {@code CLIENT} gets nothing: a client portal needs an explicit sharing mechanism,
 * the platform has none, and Phase 4 does not invent one — so a client of a firm cannot
 * reach that firm's filings merely because filings now exist. {@code SUPER_ADMIN} gets
 * nothing either, for the reason it gets nothing anywhere in casework: it has no
 * organization to be scoped to.
 */
class DocumentSecurityIT extends AbstractDocumentIT {

    private Matter matter;
    private String lawyerToken;
    private String clerkToken;
    private String clientRoleToken;
    private String platformToken;
    private String documentId;

    @BeforeEach
    void staffTheFirm() throws Exception {
        matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        lawyerToken = inviteAndActivate(matter.firm(), "ravi@sharma-legal.test", "LAWYER");
        clerkToken = inviteAndActivate(matter.firm(), "clerk@sharma-legal.test", "CLERK");
        clientRoleToken = inviteAndActivate(matter.firm(), "portal@sharma-legal.test", "CLIENT");
        platformToken = platformAdminToken("asha@sharma-legal.test");
        documentId = availableDocument(matter.firm().adminToken(), matter.caseId(),
                "Written statement.pdf", 1024);
    }

    private String admin() {
        return matter.firm().adminToken();
    }

    @Test
    void readingDocumentsIsOpenToAllStaffAndNobodyElse() throws Exception {
        allow(() -> get("/api/v1/cases/" + matter.caseId() + "/documents"),
                admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/cases/" + matter.caseId() + "/documents"),
                clientRoleToken, platformToken);

        allow(() -> get("/api/v1/documents/" + documentId), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/documents/" + documentId), clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("a client of the firm cannot download its filings — there is no sharing mechanism")
    void downloadingIsOpenToAllStaffAndNobodyElse() throws Exception {
        Supplier<MockHttpServletRequestBuilder> download =
                () -> get("/api/v1/documents/" + documentId + "/download");

        allow(download, admin(), lawyerToken, clerkToken);
        deny(download, clientRoleToken, platformToken);
    }

    @Test
    void uploadingAndCompletingAreOpenToAllStaff() throws Exception {
        Supplier<MockHttpServletRequestBuilder> register = () ->
                body(post("/api/v1/cases/" + matter.caseId() + "/documents"),
                        documentBody("Another.pdf", PDF, 1024));
        allow(register, admin(), lawyerToken, clerkToken);
        deny(register, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> complete =
                () -> post("/api/v1/documents/" + documentId + "/complete");
        allow(complete, admin(), lawyerToken, clerkToken);
        deny(complete, clientRoleToken, platformToken);
    }

    @Test
    void renamingIsOpenToAllStaff() throws Exception {
        Supplier<MockHttpServletRequestBuilder> rename = () ->
                body(put("/api/v1/documents/" + documentId),
                        "{\"filename\":\"Renamed.pdf\",\"version\":0}");

        allow(rename, admin(), lawyerToken, clerkToken);
        deny(rename, clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("only an administrator removes a document — the same shape as every other delete")
    void removalIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> remove =
                () -> delete("/api/v1/documents/" + documentId);

        deny(remove, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(remove, admin());
    }

    @Test
    @DisplayName("nothing document-related is reachable without a token")
    void everyEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/documents"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/" + documentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/" + documentId + "/download"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/documents/" + documentId + "/complete"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/documents/" + documentId))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Each token gets a freshly built request: reusing a builder stacks a second
     * Authorization header rather than replacing the first.
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
