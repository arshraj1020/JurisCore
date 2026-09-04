package com.juriscore.notifications;

import com.juriscore.billing.AbstractBillingIT;
import com.juriscore.billing.service.OverdueInvoiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notifications, end to end: a billing event happens, and somebody is told about it.
 *
 * <p>The chain under test is the whole of it — {@code InvoiceService} publishes,
 * {@code BillingNotificationListener} maps after the commit, {@code NotificationService}
 * writes, and the API returns it. Nothing here is stubbed, which is the point: an
 * after-commit listener that never fires looks exactly like a listener that fired and did
 * nothing, unless a test goes and looks.
 *
 * <p>Extends the billing fixture because every Phase 5 notification is caused by a billing
 * event; the notification API itself needs nothing from billing.
 */
class NotificationIT extends AbstractBillingIT {

    @Autowired
    private OverdueInvoiceService overdueInvoiceService;

    private long notificationCount(String email, String type) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notifications.notifications
                 WHERE recipient_user_id = ? AND notification_type = ?
                """, Long.class, userIdOf(email), type);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("issuing an invoice tells the firm's administrator about it")
    void issuingRaisesANotification() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("INVOICE_ISSUED"))
                .andExpect(jsonPath("$.data.items[0].category").value("INVOICE"))
                .andExpect(jsonPath("$.data.items[0].severity").value("INFO"))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.items[0].entityType").value("INVOICE"))
                .andExpect(jsonPath("$.data.items[0].entityId").value(invoiceId))
                .andExpect(jsonPath("$.data.items[0].actionPath").value("/invoices/" + invoiceId))
                .andExpect(jsonPath("$.data.items[0].title")
                        .value(org.hamcrest.Matchers.containsString(numberOf(invoiceId))));
    }

    @Test
    @DisplayName("a payment and a settlement each produce their own notification")
    void paymentsAndSettlementBothNotify() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        pay(token, invoiceId, "4000.00", 201);
        assertThat(notificationCount("asha@sharma-legal.test", "PAYMENT_RECEIVED")).isEqualTo(1);
        assertThat(notificationCount("asha@sharma-legal.test", "INVOICE_PAID")).isZero();

        pay(token, invoiceId, "7800.00", 201);
        assertThat(notificationCount("asha@sharma-legal.test", "PAYMENT_RECEIVED"))
                .as("every payment is its own event and its own notification")
                .isEqualTo(2);
        assertThat(notificationCount("asha@sharma-legal.test", "INVOICE_PAID")).isEqualTo(1);
    }

    @Test
    @DisplayName("an overdue invoice notifies once, however many times the sweep runs")
    void overdueNotifiesOnce() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        overdueCandidate(token, ledger.clientId());

        overdueInvoiceService.markOverdue();
        assertThat(notificationCount("asha@sharma-legal.test", "INVOICE_OVERDUE")).isEqualTo(1);

        // A rerun publishes nothing at all; but even if it did, the dedupe key would stop
        // a second notification reaching the same person.
        overdueInvoiceService.markOverdue();
        overdueInvoiceService.markOverdue();
        assertThat(notificationCount("asha@sharma-legal.test", "INVOICE_OVERDUE")).isEqualTo(1);
    }

    @Test
    @DisplayName("every administrator of the firm is told, and nobody outside it")
    void everyAdministratorIsNotified() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        inviteAndActivate(ledger.firm(), "second-admin@sharma-legal.test", "FIRM_ADMIN");
        String lawyerToken = inviteAndActivate(ledger.firm(), "ravi@sharma-legal.test", "LAWYER");

        issued(token, ledger.clientId(), null);

        assertThat(notificationCount("asha@sharma-legal.test", "INVOICE_ISSUED")).isEqualTo(1);
        assertThat(notificationCount("second-admin@sharma-legal.test", "INVOICE_ISSUED"))
                .isEqualTo(1);
        assertThat(notificationCount("ravi@sharma-legal.test", "INVOICE_ISSUED"))
                .as("Phase 5 notifies the firm's administrators about billing, not every "
                        + "member of staff")
                .isZero();

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(lawyerToken)))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    // ------------------------------------------------------------------------ isolation

    @Test
    @DisplayName("a colleague's notification is not found, exactly as another firm's is not")
    void aColleaguesNotificationIsNotFound() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String adminToken = ledger.firm().adminToken();
        String clerkToken = inviteAndActivate(ledger.firm(), "clerk@sharma-legal.test", "CLERK");
        issued(adminToken, ledger.clientId(), null);

        String theirNotificationId = jdbcTemplate.queryForObject("""
                SELECT id::text FROM notifications.notifications WHERE recipient_user_id = ?
                """, String.class, userIdOf("asha@sharma-legal.test"));

        mockMvc.perform(get("/api/v1/notifications/" + theirNotificationId)
                        .header("Authorization", bearer(clerkToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/notifications/" + theirNotificationId + "/read")
                        .header("Authorization", bearer(clerkToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/notifications/" + theirNotificationId)
                        .header("Authorization", bearer(clerkToken)))
                .andExpect(status().isNotFound());

        // And it is untouched.
        mockMvc.perform(get("/api/v1/notifications/" + theirNotificationId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(false));
    }

    @Test
    void anotherFirmsNotificationsAreInvisible() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        issued(theirs.firm().adminToken(), theirs.clientId(), null);

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void anUnknownNotificationIsNotFound() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/notifications/" + UUID.randomUUID())
                        .header("Authorization", bearer(ledger.firm().adminToken())))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------------------- reading

    @Test
    @DisplayName("unread filtering, the badge count, and marking one read")
    void readingAndUnreadFiltering() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "1000.00", 201);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(2));
        mockMvc.perform(get("/api/v1/notifications").param("unread", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(2));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.unread").value(2));

        String newest = jdbcTemplate.queryForObject("""
                SELECT id::text FROM notifications.notifications
                 WHERE recipient_user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1
                """, String.class, userIdOf("asha@sharma-legal.test"));

        mockMvc.perform(post("/api/v1/notifications/" + newest + "/read")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true))
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/notifications").param("unread", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(2));
    }

    @Test
    @DisplayName("marking one read twice does not restamp when it was read")
    void markingReadIsIdempotent() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        issued(token, ledger.clientId(), null);
        String id = jdbcTemplate.queryForObject("""
                SELECT id::text FROM notifications.notifications WHERE recipient_user_id = ?
                """, String.class, userIdOf("asha@sharma-legal.test"));

        String firstReadAt = json(mockMvc.perform(post("/api/v1/notifications/" + id + "/read")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn())
                .path("data").path("readAt").asText();

        mockMvc.perform(post("/api/v1/notifications/" + id + "/read")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readAt").value(firstReadAt));
    }

    @Test
    void readAllClearsTheBadgeAndOnlyForTheCaller() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        inviteAndActivate(ledger.firm(), "second-admin@sharma-legal.test", "FIRM_ADMIN");
        String otherAdmin = signIn("second-admin@sharma-legal.test");
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "1000.00", 201);

        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marked").value(2));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.unread").value(0));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", bearer(otherAdmin)))
                .andExpect(jsonPath("$.data.unread")
                        .value(2));

        // A second read-all has nothing left to do.
        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.marked").value(0));
    }

    @Test
    void dismissingRemovesItForGood() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        issued(token, ledger.clientId(), null);
        String id = jdbcTemplate.queryForObject("""
                SELECT id::text FROM notifications.notifications WHERE recipient_user_id = ?
                """, String.class, userIdOf("asha@sharma-legal.test"));

        mockMvc.perform(delete("/api/v1/notifications/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    @DisplayName("nothing sensitive travels in a notification: no key, no URL, no reference")
    void notificationsCarryNothingSensitive() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "1000.00", 201);

        String body = mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("UTR 220414512345")
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("dedupeKey")
                .doesNotContain("recipientUserId")
                .doesNotContain("organizationId");
    }

    @Test
    @DisplayName("a user with notifications turned off gets none, and the rest still do")
    void preferencesAreHonouredEndToEnd() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        inviteAndActivate(ledger.firm(), "second-admin@sharma-legal.test", "FIRM_ADMIN");
        String otherAdmin = signIn("second-admin@sharma-legal.test");

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":false}"))
                .andExpect(status().isOk());

        issued(token, ledger.clientId(), null);

        assertThat(notificationCount("asha@sharma-legal.test", "INVOICE_ISSUED"))
                .as("the administrator who muted invoices hears nothing")
                .isZero();
        assertThat(notificationCount("second-admin@sharma-legal.test", "INVOICE_ISSUED"))
                .as("and their colleague, who did not, still does")
                .isEqualTo(1);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(otherAdmin)))
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    @DisplayName("muting invoices does not mute payments — the switches are independent")
    void mutingOneCategoryLeavesTheOthers() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoice\":false}"))
                .andExpect(status().isOk());

        pay(token, invoiceId, "11800.00", 201);

        assertThat(notificationCount("asha@sharma-legal.test", "PAYMENT_RECEIVED")).isEqualTo(1);
        assertThat(notificationCount("asha@sharma-legal.test", "INVOICE_PAID"))
                .as("INVOICE_PAID is an INVOICE-category notification, and those are muted")
                .isZero();
    }
}
