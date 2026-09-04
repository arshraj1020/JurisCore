package com.juriscore.billing;

import com.juriscore.billing.event.InvoiceOverdueEvent;
import com.juriscore.billing.service.OverdueInvoiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one invoice transition nobody causes.
 *
 * <p>Driven directly rather than by the timer, exactly as {@code ReminderDispatchServiceIT}
 * drives the reminder sweep: a background thread mutating invoice rows while a test asserts
 * on them is the classic test that is green locally and red on a busy runner. The test
 * profile turns the schedule off for the same reason.
 */
class OverdueInvoiceIT extends AbstractBillingIT {

    @Autowired
    private OverdueInvoiceService overdueInvoiceService;

    @Test
    @DisplayName("an issued invoice past its due date becomes OVERDUE and publishes once")
    void theSweepMovesPastDueInvoices() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = overdueCandidate(token, ledger.clientId());
        events.clear();

        assertThat(overdueInvoiceService.markOverdue()).isEqualTo(1);

        assertThat(statusOf(invoiceId)).isEqualTo("OVERDUE");
        InvoiceOverdueEvent event = events.require(InvoiceOverdueEvent.class);
        assertThat(event.eventType()).isEqualTo("invoice.overdue");
        assertThat(event.getAmountDue()).isEqualByComparingTo("11800.00");
        assertThat(event.getInvoiceNumber()).isEqualTo(numberOf(invoiceId));
    }

    @Test
    @DisplayName("a rerun finds nothing and publishes nothing — the status is the idempotency key")
    void theSweepIsIdempotent() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String invoiceId = overdueCandidate(ledger.firm().adminToken(), ledger.clientId());

        assertThat(overdueInvoiceService.markOverdue()).isEqualTo(1);
        events.clear();

        assertThat(overdueInvoiceService.markOverdue())
                .as("the claim only matches ISSUED and PARTIALLY_PAID, so a second run "
                        + "sees nothing")
                .isZero();
        assertThat(overdueInvoiceService.markOverdue()).isZero();

        assertThat(events.latest(InvoiceOverdueEvent.class))
                .as("a rerun must not publish a second overdue event")
                .isEmpty();
        assertThat(statusOf(invoiceId)).isEqualTo("OVERDUE");
    }

    @Test
    @DisplayName("an invoice due today is not late today — a firm means the whole of the day")
    void theBoundaryIsStrictlyBefore() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), null);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        issue(token, invoiceId, versionOf(invoiceId), today.minusDays(30), today, 200);

        assertThat(overdueInvoiceService.markOverdue(today)).isZero();
        assertThat(statusOf(invoiceId)).isEqualTo("ISSUED");

        assertThat(overdueInvoiceService.markOverdue(today.plusDays(1))).isEqualTo(1);
        assertThat(statusOf(invoiceId)).isEqualTo("OVERDUE");
    }

    @Test
    @DisplayName("drafts, settled and cancelled invoices are never swept")
    void theSweepLeavesEverythingElseAlone() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        LocalDate longAgo = LocalDate.now(ZoneOffset.UTC).minusDays(90);

        String stillADraft = draft(token, ledger.clientId(), null);

        String settled = overdueCandidate(token, ledger.clientId());
        pay(token, settled, "11800.00", 201);

        String cancelled = overdueCandidate(token, ledger.clientId());
        cancel(token, cancelled, versionOf(cancelled), 200);

        assertThat(overdueInvoiceService.markOverdue(longAgo.plusDays(200))).isZero();

        assertThat(statusOf(stillADraft)).isEqualTo("DRAFT");
        assertThat(statusOf(settled)).isEqualTo("PAID");
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a part-paid invoice past its date goes overdue and keeps its payments")
    void aPartPaidInvoiceCanGoOverdue() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = overdueCandidate(token, ledger.clientId());
        pay(token, invoiceId, "4000.00", 201);
        assertThat(statusOf(invoiceId)).isEqualTo("PARTIALLY_PAID");
        events.clear();

        assertThat(overdueInvoiceService.markOverdue()).isEqualTo(1);

        assertThat(statusOf(invoiceId)).isEqualTo("OVERDUE");
        assertThat(paidOn(invoiceId)).isEqualByComparingTo("4000.00");
        assertThat(events.require(InvoiceOverdueEvent.class).getAmountDue())
                .as("the event carries what is still outstanding, not the invoice total")
                .isEqualByComparingTo("7800.00");
    }

    @Test
    @DisplayName("money arriving after the sweep still settles the invoice")
    void anOverdueInvoiceCanStillBeSettled() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = overdueCandidate(token, ledger.clientId());
        overdueInvoiceService.markOverdue();

        pay(token, invoiceId, "11800.00", 201);

        assertThat(statusOf(invoiceId)).isEqualTo("PAID");
        assertThat(overdueInvoiceService.markOverdue())
                .as("and it is not swept again afterwards")
                .isZero();
    }

    @Test
    @DisplayName("the sweep crosses firms — it is the platform's job, not one tenant's")
    void theSweepCoversEveryFirm() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String a = overdueCandidate(mine.firm().adminToken(), mine.clientId());
        String b = overdueCandidate(theirs.firm().adminToken(), theirs.clientId());

        assertThat(overdueInvoiceService.markOverdue()).isEqualTo(2);

        assertThat(statusOf(a)).isEqualTo("OVERDUE");
        assertThat(statusOf(b)).isEqualTo("OVERDUE");
    }
}
