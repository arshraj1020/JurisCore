package com.juriscore.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.function.Supplier;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The billing role matrix, one assertion per cell.
 *
 * <p>The shape asserted here, and the reasoning behind it:
 *
 * <ul>
 *   <li><strong>Reading</strong> is open to all firm staff. A lawyer needs to know whether
 *       their client has paid.</li>
 *   <li><strong>Drafting</strong> is open to {@code FIRM_ADMIN} and {@code CLERK} —
 *       preparing a bill is case-maintenance work, and a clerk already maintains tasks and
 *       documents. A {@code LAWYER} is not given it: nothing in the repository says a fee
 *       earner drafts their own bills, and assuming it would broaden a permission on a
 *       guess.</li>
 *   <li><strong>Issuing, cancelling and recording payments</strong> are the administrator's
 *       alone. These are the three actions where a mistake reaches the client or the firm's
 *       books, and the platform already reserves its consequential verbs for
 *       {@code FIRM_ADMIN}.</li>
 *   <li><strong>{@code CLIENT}</strong> reaches none of it. There is no client billing
 *       portal in Phase 5.</li>
 *   <li><strong>{@code SUPER_ADMIN}</strong> reaches none of it either, and not because a
 *       role list excludes it: it has no organization of its own, so
 *       {@code requireOrganizationId()} refuses it.</li>
 * </ul>
 */
class InvoiceSecurityIT extends AbstractBillingIT {

    private Ledger ledger;
    private String lawyerToken;
    private String clerkToken;
    private String clientRoleToken;
    private String platformToken;
    private String invoiceId;

    @BeforeEach
    void staffTheFirm() throws Exception {
        ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        lawyerToken = inviteAndActivate(ledger.firm(), "ravi@sharma-legal.test", "LAWYER");
        clerkToken = inviteAndActivate(ledger.firm(), "clerk@sharma-legal.test", "CLERK");
        clientRoleToken = inviteAndActivate(ledger.firm(), "portal@sharma-legal.test", "CLIENT");
        platformToken = platformAdminToken("asha@sharma-legal.test");
        invoiceId = draft(admin(), ledger.clientId(), null);
    }

    private String admin() {
        return ledger.firm().adminToken();
    }

    @Test
    void readingIsOpenToAllStaffAndNobodyElse() throws Exception {
        allow(() -> get("/api/v1/invoices"), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/invoices"), clientRoleToken, platformToken);

        allow(() -> get("/api/v1/invoices/" + invoiceId), admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/invoices/" + invoiceId), clientRoleToken, platformToken);

        allow(() -> get("/api/v1/invoices/" + invoiceId + "/payments"),
                admin(), lawyerToken, clerkToken);
        deny(() -> get("/api/v1/invoices/" + invoiceId + "/payments"),
                clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("a clerk drafts and edits invoices; a lawyer does not")
    void draftingIsForAdministratorsAndClerks() throws Exception {
        Supplier<MockHttpServletRequestBuilder> create = () ->
                body(post("/api/v1/invoices"), invoiceBody(ledger.clientId(), null));
        allow(create, admin(), clerkToken);
        deny(create, lawyerToken, clientRoleToken, platformToken);

        Supplier<MockHttpServletRequestBuilder> edit = () ->
                body(patch("/api/v1/invoices/" + invoiceId),
                        "{\"version\":%d,\"notes\":\"Edited\"}".formatted(versionOf(invoiceId)));
        allow(edit, admin(), clerkToken);
        deny(edit, lawyerToken, clientRoleToken, platformToken);
    }

    @Test
    @DisplayName("issuing a bill to a client is the administrator's alone")
    void issuingIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> issue = () ->
                body(post("/api/v1/invoices/" + invoiceId + "/issue"),
                        "{\"version\":%d}".formatted(versionOf(invoiceId)));

        deny(issue, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(issue, admin());
    }

    @Test
    void cancellingIsForAdministratorsOnly() throws Exception {
        Supplier<MockHttpServletRequestBuilder> cancel = () ->
                body(post("/api/v1/invoices/" + invoiceId + "/cancel"),
                        "{\"version\":%d}".formatted(versionOf(invoiceId)));

        deny(cancel, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(cancel, admin());
    }

    @Test
    @DisplayName("deciding that money has arrived is the administrator's alone")
    void recordingPaymentsIsForAdministratorsOnly() throws Exception {
        issue(admin(), invoiceId, versionOf(invoiceId), null, null, 200);
        Supplier<MockHttpServletRequestBuilder> pay = () ->
                body(post("/api/v1/invoices/" + invoiceId + "/payments"),
                        paymentBody("100.00", "INR"));

        deny(pay, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(pay, admin());
    }

    @Test
    @DisplayName("the firm's own billing settings are the administrator's to read and write")
    void billingSettingsAreForAdministratorsOnly() throws Exception {
        deny(() -> get("/api/v1/billing/profile"),
                lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(() -> get("/api/v1/billing/profile"), admin());

        Supplier<MockHttpServletRequestBuilder> edit = () ->
                body(patch("/api/v1/billing/profile"), "{\"legalName\":\"Sharma LLP\"}");
        deny(edit, lawyerToken, clerkToken, clientRoleToken, platformToken);
        allow(edit, admin());
    }

    @Test
    @DisplayName("nothing billing-related is reachable without a token")
    void everyEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/invoices")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/payments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/issue"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/billing/profile")).andExpect(status().isUnauthorized());
    }

    /** Each token gets a freshly built request: reusing a builder stacks a second header. */
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
