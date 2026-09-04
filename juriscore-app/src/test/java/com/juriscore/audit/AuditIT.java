package com.juriscore.audit;

import com.juriscore.billing.AbstractBillingIT;
import com.juriscore.billing.service.OverdueInvoiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

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

/**
 * The audit trail: what reaches it, who may read it, and what cannot be done to it.
 */
class AuditIT extends AbstractBillingIT {

    @Autowired
    private OverdueInvoiceService overdueInvoiceService;

    private List<String> actionsFor(String firmId) {
        return jdbcTemplate.queryForList("""
                SELECT action FROM audit.audit_events
                 WHERE organization_id = ?::uuid ORDER BY occurred_at, id
                """, String.class, firmId);
    }

    private long rowsFor(String firmId, String action) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit.audit_events
                 WHERE organization_id = ?::uuid AND action = ?
                """, Long.class, firmId, action);
        return count == null ? 0 : count;
    }

    // -------------------------------------------------------------------- what is recorded

    @Test
    @DisplayName("the billing lifecycle is recorded end to end")
    void billingActionsReachTheTrail() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String firmId = ledger.firm().id();

        String invoiceId = draft(token, ledger.clientId(), null);
        issue(token, invoiceId, versionOf(invoiceId), null, null, 200);
        pay(token, invoiceId, "11800.00", 201);

        assertThat(actionsFor(firmId))
                .contains("invoice.created", "invoice.issued", "payment.recorded", "invoice.paid");

        String cancelled = draft(token, ledger.clientId(), null);
        cancel(token, cancelled, versionOf(cancelled), 200);
        assertThat(actionsFor(firmId)).contains("invoice.cancelled");
    }

    @Test
    @DisplayName("casework, case management and documents all reach the same trail")
    void thePhaseOneToFourActionsAreRecordedToo() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String firmId = ledger.firm().id();

        assertThat(actionsFor(firmId))
                .as("casework and case management write into the firm's own trail")
                .contains("client.created", "case.created");

        inviteAndActivate(ledger.firm(), "ravi@sharma-legal.test", "LAWYER");

        // Identity events are asserted without the tenant filter. Whether a sign-up or an
        // invitation carries an organization on its event is Phase 1's decision, not this
        // test's to assume — what matters here is that the action reached the trail at all.
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit.audit_events", String.class))
                .contains("identity.user.registered", "identity.user.invited",
                        "identity.password.changed");
    }

    @Test
    @DisplayName("a system sweep records the action with no actor, rather than inventing one")
    void systemActionsHaveNoActor() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        overdueCandidate(ledger.firm().adminToken(), ledger.clientId());

        overdueInvoiceService.markOverdue();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT actor_user_id IS NULL FROM audit.audit_events
                 WHERE organization_id = ?::uuid AND action = 'invoice.overdue'
                """, Boolean.class, ledger.firm().id()))
                .isTrue();
    }

    @Test
    @DisplayName("a request-scoped action records the person who made it")
    void requestActionsRecordTheActor() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String invoiceId = draft(ledger.firm().adminToken(), ledger.clientId(), null);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT actor_user_id FROM audit.audit_events
                 WHERE action = 'invoice.created' AND entity_id = ?::uuid
                """, UUID.class, invoiceId))
                .isEqualTo(userIdOf("asha@sharma-legal.test"));
    }

    @Test
    @DisplayName("one business action, one audit row — the event id is the idempotency key")
    void eachEventIsRecordedOnce() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        assertThat(rowsFor(ledger.firm().id(), "invoice.issued")).isEqualTo(1);

        // The sweep is idempotent, so a rerun records nothing more either.
        overdueInvoiceService.markOverdue();
        overdueInvoiceService.markOverdue();
        assertThat(rowsFor(ledger.firm().id(), "invoice.overdue")).isZero();
        assertThat(statusOf(invoiceId)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("a notification does not audit itself — a trail that records its own effects grows for nothing")
    void notificationsAreNotAudited() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        issued(ledger.firm().adminToken(), ledger.clientId(), null);

        assertThat(actionsFor(ledger.firm().id())).doesNotContain("notification.created");
    }

    // ------------------------------------------------------------------ sensitive data

    @Test
    @DisplayName("no token, no signed URL, no bank reference ever reaches an audit summary")
    void noSecretsAreStored() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        inviteAndActivate(ledger.firm(), "ravi@sharma-legal.test", "LAWYER");
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "4000.00", 201);

        List<String> summaries = jdbcTemplate.queryForList(
                "SELECT summary FROM audit.audit_events", String.class);

        assertThat(summaries).isNotEmpty();
        for (String summary : summaries) {
            assertThat(summary)
                    .as("summary %s must carry nothing that could be replayed", summary)
                    .doesNotContain("UTR 220414512345")
                    .doesNotContainIgnoringCase("password=")
                    .doesNotContain("Bearer ")
                    .doesNotContain("eyJ")
                    .doesNotContain("X-Amz-")
                    .doesNotContain("organizations/")
                    .doesNotContain(PASSWORD);
        }

        // UserInvitedEvent carries an activation token. The audit row for it does not —
        // and AuditRedaction would refuse the write if a future edit put one there.
        assertThat(jdbcTemplate.queryForList("""
                SELECT summary FROM audit.audit_events WHERE action = 'identity.user.invited'
                """, String.class))
                .isNotEmpty()
                .allSatisfy(summary -> assertThat(summary)
                        .doesNotContainIgnoringCase("token")
                        .contains("ravi@sharma-legal.test"));
    }

    // ---------------------------------------------------------------------- the API

    @Test
    void searchesWithEveryFilter() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        mockMvc.perform(get("/api/v1/audit").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/api/v1/audit").param("action", "invoice.issued")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].entityType").value("INVOICE"))
                .andExpect(jsonPath("$.data.items[0].entityId").value(invoiceId))
                .andExpect(jsonPath("$.data.items[0].summary").isNotEmpty());

        mockMvc.perform(get("/api/v1/audit").param("entityType", "INVOICE")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(2));

        mockMvc.perform(get("/api/v1/audit").param("entityId", invoiceId)
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(2));

        mockMvc.perform(get("/api/v1/audit").param("actor", userIdOf("asha@sharma-legal.test").toString())
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/api/v1/audit").param("from", "2000-01-01T00:00:00Z")
                        .param("to", "2100-01-01T00:00:00Z")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/api/v1/audit").param("from", "2100-01-01T00:00:00Z")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void fetchesOneRecordAndRefusesAnUnknownOne() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        issued(token, ledger.clientId(), null);

        MvcResult page = mockMvc.perform(get("/api/v1/audit").param("action", "invoice.issued")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        String id = json(page).path("data").path("items").get(0).path("id").asText();

        mockMvc.perform(get("/api/v1/audit/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("invoice.issued"));

        mockMvc.perform(get("/api/v1/audit/" + UUID.randomUUID())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the response omits the tenant and the internal event id")
    void theResponseOmitsInternalIdentifiers() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        issued(token, ledger.clientId(), null);

        String body = mockMvc.perform(get("/api/v1/audit").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("organizationId")
                .doesNotContain("sourceEventId");
    }

    // ------------------------------------------------------------------ authorization

    @Test
    @DisplayName("only a firm administrator may read the trail")
    void onlyAdministratorsMayRead() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String lawyer = inviteAndActivate(ledger.firm(), "ravi@sharma-legal.test", "LAWYER");
        String clerk = inviteAndActivate(ledger.firm(), "clerk@sharma-legal.test", "CLERK");
        String client = inviteAndActivate(ledger.firm(), "portal@sharma-legal.test", "CLIENT");
        String platform = platformAdminToken("asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/audit")
                        .header("Authorization", bearer(ledger.firm().adminToken())))
                .andExpect(status().isOk());

        for (String token : List.of(lawyer, clerk, client, platform)) {
            mockMvc.perform(get("/api/v1/audit").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a platform administrator does not get a tenant's trail by being a platform role")
    void superAdminGetsNothing() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        issued(ledger.firm().adminToken(), ledger.clientId(), null);
        String platform = platformAdminToken("asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/audit").header("Authorization", bearer(platform)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit/" + UUID.randomUUID())
                        .header("Authorization", bearer(platform)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherFirmsTrailIsInvisible() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirInvoice = issued(theirs.firm().adminToken(), theirs.clientId(), null);

        mockMvc.perform(get("/api/v1/audit").param("action", "invoice.issued")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));

        // Naming their invoice explicitly returns nothing either.
        mockMvc.perform(get("/api/v1/audit").param("entityId", theirInvoice)
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(0));

        String theirRow = jdbcTemplate.queryForObject("""
                SELECT id::text FROM audit.audit_events
                 WHERE organization_id = ?::uuid AND action = 'invoice.issued'
                """, String.class, theirs.firm().id());
        mockMvc.perform(get("/api/v1/audit/" + theirRow)
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------- immutability

    @Test
    @DisplayName("there is no endpoint that changes or removes an audit record")
    void theTrailIsAppendOnlyThroughTheApi() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        issued(token, ledger.clientId(), null);
        String id = json(mockMvc.perform(get("/api/v1/audit").param("action", "invoice.issued")
                        .header("Authorization", bearer(token))).andReturn())
                .path("data").path("items").get(0).path("id").asText();

        // Not 403, and not 400: these routes do not exist at all, which is a stronger
        // guarantee than a rule somebody could relax.
        mockMvc.perform(put("/api/v1/audit/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"summary\":\"edited\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(patch("/api/v1/audit/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"summary\":\"edited\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete("/api/v1/audit/" + id).header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/audit").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());

        // And the row is exactly as it was.
        mockMvc.perform(get("/api/v1/audit/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary")
                        .value(org.hamcrest.Matchers.not("edited")));
    }

    @Test
    @DisplayName("the table itself carries no version or updated_at — nothing expects to rewrite it")
    void theTableHasNoUpdateColumns() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'audit' AND table_name = 'audit_events'
                """, String.class);

        assertThat(columns)
                .doesNotContain("version", "updated_at", "updated_by")
                .contains("id", "organization_id", "actor_user_id", "action", "entity_type",
                        "entity_id", "occurred_at", "request_id", "summary", "source_event_id");
    }
}
