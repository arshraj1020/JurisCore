package com.juriscore.app.notification;

import com.juriscore.billing.event.InvoiceCancelledEvent;
import com.juriscore.billing.event.InvoiceIssuedEvent;
import com.juriscore.billing.event.InvoiceOverdueEvent;
import com.juriscore.billing.event.InvoicePaidEvent;
import com.juriscore.billing.event.PaymentRecordedEvent;
import com.juriscore.notifications.domain.NotificationType;
import com.juriscore.notifications.service.NotificationRequest;
import com.juriscore.notifications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Turns billing events into notifications.
 *
 * <h2>Why a listener and not a call from the billing service</h2>
 *
 * <p>Two reasons, and the second is the one that matters. The first is ordinary
 * decoupling: {@code InvoiceService} has no idea anybody is being told, so adding or
 * removing a notification changes nothing about issuing an invoice.
 *
 * <p>The second is transactional. {@code AFTER_COMMIT} means a notification is only ever
 * raised for something that actually happened. Calling {@code NotificationService} from
 * inside {@code InvoiceService.issue} would put the notification in the same transaction
 * as the issue — so an invoice that failed to commit would still have told six people it
 * went out, or, worse, a notification failure would roll back a perfectly good invoice.
 *
 * <h2>Not @Async, deliberately</h2>
 *
 * <p>{@code EventLogListener} was {@code @Async} and this is not. An after-commit listener
 * runs on the request thread, which is what keeps ordering predictable and keeps
 * integration tests from racing a background thread — the same reason the test suite's own
 * {@code CapturingEventListener} is synchronous. The work is a handful of inserts;
 * {@code NotificationService.raise} already isolates each one in its own transaction, so a
 * failure here cannot reach back into the business operation.
 *
 * <h2>Who is told</h2>
 *
 * <p>The firm's active administrators, including whoever performed the action. Suppressing
 * self-notification reads tidier and was rejected: a great many Indian law firms are a
 * single practitioner who is also the only {@code FIRM_ADMIN}, and for them the entire
 * feature would silently do nothing. The cost is that an administrator who issues an
 * invoice also sees it in their feed, which is mild noise and switchable off per category.
 *
 * <h2>The mapping is explicit, and short</h2>
 *
 * <p>Five events out of more than twenty the platform publishes. A notification for every
 * event is a feed nobody reads, which is worse than no feed. Every entry below has a
 * dedupe key derived from the business fact rather than the delivery, so a repeated overdue
 * sweep, a retried publish or a second application instance cannot notify twice.
 */
@Component
@RequiredArgsConstructor
public class BillingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(BillingNotificationListener.class);

    private final NotificationService notifications;
    private final FirmStaffDirectory staff;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceIssued(InvoiceIssuedEvent event) {
        notifyAdministrators(event.organizationId(), NotificationType.INVOICE_ISSUED,
                "Invoice " + event.getInvoiceNumber() + " issued",
                "Invoice " + event.getInvoiceNumber() + " for "
                        + money(event.getTotalAmount(), event.getCurrency())
                        + " has been issued, due " + event.getDueDate() + ".",
                event.getInvoiceId(),
                // One per invoice, ever: an invoice can only be issued once.
                "invoice.issued:" + event.getInvoiceId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRecorded(PaymentRecordedEvent event) {
        notifyAdministrators(event.organizationId(), NotificationType.PAYMENT_RECEIVED,
                "Payment recorded against " + event.getInvoiceNumber(),
                money(event.getAmount(), event.getCurrency()) + " recorded against invoice "
                        + event.getInvoiceNumber() + ". "
                        + money(event.getAmountDue(), event.getCurrency()) + " still outstanding.",
                event.getInvoiceId(),
                // Keyed on the payment, not the invoice: a second payment is a second thing
                // worth hearing about, and every payment has its own id.
                "payment.recorded:" + event.getPaymentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoicePaid(InvoicePaidEvent event) {
        notifyAdministrators(event.organizationId(), NotificationType.INVOICE_PAID,
                "Invoice " + event.getInvoiceNumber() + " settled",
                "Invoice " + event.getInvoiceNumber() + " has been paid in full ("
                        + money(event.getTotalAmount(), event.getCurrency()) + ").",
                event.getInvoiceId(),
                "invoice.paid:" + event.getInvoiceId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceOverdue(InvoiceOverdueEvent event) {
        notifyAdministrators(event.organizationId(), NotificationType.INVOICE_OVERDUE,
                "Invoice " + event.getInvoiceNumber() + " is overdue",
                "Invoice " + event.getInvoiceNumber() + " was due on " + event.getDueDate()
                        + " and " + money(event.getAmountDue(), event.getCurrency())
                        + " is still outstanding.",
                event.getInvoiceId(),
                // Once per invoice, not once per sweep. An invoice that goes overdue,
                // takes a part payment and goes overdue again publishes a second event —
                // that is a real transition — but nobody needs telling twice.
                "invoice.overdue:" + event.getInvoiceId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceCancelled(InvoiceCancelledEvent event) {
        notifyAdministrators(event.organizationId(), NotificationType.INVOICE_CANCELLED,
                "Invoice " + event.getInvoiceNumber() + " cancelled",
                "Invoice " + event.getInvoiceNumber() + " has been withdrawn.",
                event.getInvoiceId(),
                "invoice.cancelled:" + event.getInvoiceId());
    }

    private void notifyAdministrators(UUID organizationId, NotificationType type, String title,
                                      String message, UUID invoiceId, String dedupeKey) {
        List<UUID> recipients = staff.activeAdministrators(organizationId);
        if (recipients.isEmpty()) {
            log.debug("No active administrator in organization {} to notify about {}",
                    organizationId, type);
            return;
        }
        for (UUID recipient : recipients) {
            notifications.raise(NotificationRequest.to(organizationId, recipient, type)
                    .saying(title, message)
                    .about("INVOICE", invoiceId)
                    // A relative in-app path. Never a signed URL: this row outlives any
                    // credential that could be put in it.
                    .linkingTo("/invoices/" + invoiceId)
                    // Per recipient, because the unique index is (recipient, dedupe_key).
                    .onceFor(dedupeKey)
                    .build());
        }
    }

    /** Amount and code, in that order. No thousands separator: this is data, not a report. */
    private static String money(BigDecimal amount, String currency) {
        return amount + " " + currency;
    }
}
