package com.juriscore.app.audit;

import com.juriscore.audit.service.AuditTrail;
import com.juriscore.billing.event.InvoiceCancelledEvent;
import com.juriscore.billing.event.InvoiceCreatedEvent;
import com.juriscore.billing.event.InvoiceIssuedEvent;
import com.juriscore.billing.event.InvoiceOverdueEvent;
import com.juriscore.billing.event.InvoicePaidEvent;
import com.juriscore.billing.event.PaymentRecordedEvent;
import com.juriscore.casemanagement.event.DeadlineCompletedEvent;
import com.juriscore.casemanagement.event.DeadlineCreatedEvent;
import com.juriscore.casemanagement.event.HearingScheduledEvent;
import com.juriscore.casemanagement.event.HearingStatusChangedEvent;
import com.juriscore.casemanagement.event.ReminderScheduledEvent;
import com.juriscore.casemanagement.event.ReminderTriggeredEvent;
import com.juriscore.casemanagement.event.TaskCompletedEvent;
import com.juriscore.casemanagement.event.TaskCreatedEvent;
import com.juriscore.casework.event.CaseCreatedEvent;
import com.juriscore.casework.event.CaseLawyerAssignedEvent;
import com.juriscore.casework.event.CaseLawyerUnassignedEvent;
import com.juriscore.casework.event.CaseStatusChangedEvent;
import com.juriscore.casework.event.ClientCreatedEvent;
import com.juriscore.common.event.DomainEvent;
import com.juriscore.documents.event.DocumentCreatedEvent;
import com.juriscore.documents.event.DocumentDeletedEvent;
import com.juriscore.documents.event.DocumentDownloadRequestedEvent;
import com.juriscore.documents.event.DocumentUploadCompletedEvent;
import com.juriscore.identity.event.PasswordChangedEvent;
import com.juriscore.identity.event.PasswordResetRequestedEvent;
import com.juriscore.identity.event.UserInvitedEvent;
import com.juriscore.identity.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * The single bridge from the event bus to the audit trail.
 *
 * <h2>Why it lives here and not in the audit module</h2>
 *
 * <p>Because it is the only class that needs to know what every module's events look like,
 * and {@code juriscore-app} is the module that already has every module in view — it is
 * the one that assembles them. {@code juriscore-audit} therefore depends only on
 * {@code juriscore-common} and stays testable on its own, exactly as
 * {@code juriscore-notifications} does.
 *
 * <p>The alternative — an {@code Auditable} interface in {@code juriscore-common} that
 * every Phase 1–4 event implements — was rejected. It would mean editing more than twenty
 * working event classes to install a Phase 5 concern into them, and it would scatter the
 * decision about what gets audited across four modules. Here the whole policy is one
 * switch that can be read top to bottom.
 *
 * <h2>Not @Async, and that is load-bearing</h2>
 *
 * <p>{@code AuditTrail} reads the actor from {@code CurrentUser}. An after-commit listener
 * runs synchronously on the request thread, so the {@code SecurityContextHolder} is still
 * populated and the actor is exactly who made the request. Moving this to
 * {@code @Async} would silently drop the security context and every audit row would be
 * attributed to nobody — a failure that looks like working software. This class replaces
 * {@code EventLogListener}, the Phase 1 placeholder that logged every event and was
 * {@code @Async} precisely because it needed no context.
 *
 * <h2>What is not audited, and why</h2>
 *
 * <ul>
 *   <li><strong>{@code notification.created}.</strong> Auditing a notification about an
 *       invoice, next to the audit row for the invoice, is noise — and an audit trail that
 *       records its own downstream effects grows without saying anything new.</li>
 *   <li><strong>{@code court.created}, {@code document.updated}.</strong> Reference-data
 *       maintenance and renaming a file. The trail is for actions somebody may be asked
 *       to answer for, and a shorter trail with only those in it is one somebody will
 *       actually read.</li>
 *   <li><strong>Sign-in success and failure.</strong> Not because they do not belong —
 *       they plainly do — but because Phase 1 publishes no event for them.
 *       {@code AuthService} records failures directly on the user row through
 *       {@code LoginAttemptRecorder}. Adding an event to it is a change to working
 *       authentication code for a Phase 5 concern, so it is left for a follow-up and
 *       recorded as a gap rather than faked here. The same applies to logout and
 *       session revocation.</li>
 * </ul>
 *
 * <h2>Secrets never reach a summary</h2>
 *
 * <p>Two of these events carry credentials: {@code UserInvitedEvent} has an activation
 * token and {@code PasswordResetRequestedEvent} has a reset token. Both are audited — a
 * password reset request is exactly the kind of thing a trail exists for — and neither
 * summary touches the token. {@code AuditRedaction} then checks every summary before it is
 * written, so a future edit that puts one in fails loudly instead of quietly storing it.
 */
@Component
@RequiredArgsConstructor
public class DomainEventAuditListener {

    private final AuditTrail auditTrail;

    /** What a domain event says, in the trail's vocabulary. Null means "not audited". */
    private record Entry(String entityType, UUID entityId, String summary) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEvent event) {
        Entry entry = describe(event);
        if (entry == null) {
            return;
        }
        auditTrail.record(event.organizationId(), event.eventType(), entry.entityType(),
                entry.entityId(), event.occurredAt(), entry.summary(), event.eventId());
    }

    /**
     * The whole policy, in one switch.
     *
     * <p>Pattern matching over the event types rather than a map keyed on
     * {@code eventType()}: this way the compiler checks every field access, so an event
     * that changes shape breaks the build instead of producing an audit row with a null in
     * it.
     */
    private Entry describe(DomainEvent event) {
        return switch (event) {

            // ------------------------------------------------------------ identity
            case UserRegisteredEvent e -> new Entry("USER", e.getUserId(),
                    "Signed up: " + e.getFullName() + " <" + e.getEmail() + "> as " + e.getRole()
                            + (e.isNewOrganization() ? ", creating a new firm" : ""));
            case UserInvitedEvent e -> new Entry("USER", e.getUserId(),
                    // The activation token on this event is deliberately not here.
                    "Invited " + e.getFullName() + " <" + e.getEmail() + "> as " + e.getRole());
            case PasswordChangedEvent e -> new Entry("USER", e.getUserId(),
                    "Password changed for " + e.getEmail()
                            + (e.isViaResetLink() ? " via a reset link" : " while signed in"));
            case PasswordResetRequestedEvent e -> new Entry("USER", e.getUserId(),
                    // The reset token on this event is deliberately not here.
                    "Password reset requested for " + e.getEmail());

            // ------------------------------------------------------------ casework
            case ClientCreatedEvent e -> new Entry("CLIENT", e.getClientId(),
                    "Client added: " + e.getDisplayName());
            case CaseCreatedEvent e -> new Entry("CASE", e.getCaseId(),
                    "Matter opened: " + e.getCaseNumber());
            case CaseStatusChangedEvent e -> new Entry("CASE", e.getCaseId(),
                    "Matter " + e.getCaseNumber() + " moved from " + e.getPreviousStatus()
                            + " to " + e.getNewStatus());
            case CaseLawyerAssignedEvent e -> new Entry("CASE", e.getCaseId(),
                    "Lawyer " + e.getLawyerUserId() + " assigned to " + e.getCaseNumber()
                            + (e.isLead() ? " as lead" : ""));
            case CaseLawyerUnassignedEvent e -> new Entry("CASE", e.getCaseId(),
                    "Lawyer " + e.getLawyerUserId() + " removed from " + e.getCaseNumber());

            // ----------------------------------------------------- case management
            case HearingScheduledEvent e -> new Entry("HEARING", e.getHearingId(),
                    "Hearing listed for " + e.getScheduledAt() + " on matter " + e.getCaseId());
            case HearingStatusChangedEvent e -> new Entry("HEARING", e.getHearingId(),
                    "Hearing moved from " + e.getPreviousStatus() + " to " + e.getNewStatus());
            case TaskCreatedEvent e -> new Entry("TASK", e.getTaskId(),
                    "Task created: " + e.getTitle());
            case TaskCompletedEvent e -> new Entry("TASK", e.getTaskId(),
                    "Task completed: " + e.getTitle());
            case DeadlineCreatedEvent e -> new Entry("DEADLINE", e.getDeadlineId(),
                    "Deadline set for " + e.getDueAt() + ": " + e.getTitle());
            case DeadlineCompletedEvent e -> new Entry("DEADLINE", e.getDeadlineId(),
                    "Deadline completed: " + e.getTitle());
            case ReminderScheduledEvent e -> new Entry("REMINDER", e.getReminderId(),
                    "Reminder scheduled for " + e.getRemindAt() + " via " + e.getChannel());
            case ReminderTriggeredEvent e -> new Entry("REMINDER", e.getReminderId(),
                    "Reminder due " + e.getScheduledFor() + " published via " + e.getChannel());

            // ----------------------------------------------------------- documents
            case DocumentCreatedEvent e -> new Entry("DOCUMENT", e.getDocumentId(),
                    "Document registered on matter " + e.getCaseId() + ": " + e.getFilename());
            case DocumentUploadCompletedEvent e -> new Entry("DOCUMENT", e.getDocumentId(),
                    "Document upload confirmed at " + e.getSize() + " bytes: " + e.getFilename());
            case DocumentDeletedEvent e -> new Entry("DOCUMENT", e.getDocumentId(),
                    "Document removed: " + e.getFilename());
            // Who read which filing is the record a firm is eventually asked for, and this
            // event exists because Phase 4 anticipated exactly this trail.
            case DocumentDownloadRequestedEvent e -> new Entry("DOCUMENT", e.getDocumentId(),
                    "Document download link issued on matter " + e.getCaseId());

            // ------------------------------------------------------------- billing
            case InvoiceCreatedEvent e -> new Entry("INVOICE", e.getInvoiceId(),
                    "Invoice " + e.getInvoiceNumber() + " drafted for "
                            + e.getTotalAmount() + " " + e.getCurrency());
            case InvoiceIssuedEvent e -> new Entry("INVOICE", e.getInvoiceId(),
                    "Invoice " + e.getInvoiceNumber() + " issued for " + e.getTotalAmount()
                            + " " + e.getCurrency() + ", due " + e.getDueDate());
            case InvoiceCancelledEvent e -> new Entry("INVOICE", e.getInvoiceId(),
                    "Invoice " + e.getInvoiceNumber() + " cancelled");
            case PaymentRecordedEvent e -> new Entry("PAYMENT", e.getPaymentId(),
                    // Amount and balance. The cheque number or bank narration is not on the
                    // event and does not belong in a trail a firm administrator can page.
                    "Payment of " + e.getAmount() + " " + e.getCurrency() + " recorded against "
                            + e.getInvoiceNumber() + "; " + e.getAmountDue() + " outstanding");
            case InvoicePaidEvent e -> new Entry("INVOICE", e.getInvoiceId(),
                    "Invoice " + e.getInvoiceNumber() + " settled in full ("
                            + e.getTotalAmount() + " " + e.getCurrency() + ")");
            case InvoiceOverdueEvent e -> new Entry("INVOICE", e.getInvoiceId(),
                    "Invoice " + e.getInvoiceNumber() + " passed its due date of "
                            + e.getDueDate() + " with " + e.getAmountDue() + " "
                            + e.getCurrency() + " outstanding");

            default -> null;
        };
    }
}
