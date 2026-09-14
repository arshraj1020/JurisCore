package com.juriscore.billing.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A bill from a firm to one of its clients.
 *
 * <p>{@code clientId} and {@code caseId} point into {@code casework} and so carry no
 * foreign key, following the rule hearings, tasks and documents already follow: they are
 * validated through {@code ClientService} and {@code CaseAccess} before anything is
 * written. The line items do carry one, because they live in this schema and have no life
 * without the invoice above them.
 *
 * <p>The four money fields are derived, never dictated. {@code InvoiceCalculator} computes
 * them from the lines and {@code ck_invoices_total} makes the database refuse a row where
 * {@code total ≠ subtotal + tax − discount}; there is no request DTO with a
 * {@code totalAmount} field for a client to disagree through.
 *
 * <p>{@code invoiceNumber} is issued by {@code InvoiceNumberGenerator} and mapped
 * {@code updatable = false}. Unlike a document's storage key — which is derived from the
 * generated row id and so can only be stamped afterwards — an invoice number needs
 * nothing from the row, so it is correct on the insert and never written again.
 */
@Entity
@Table(name = "invoices", schema = "billing")
@Getter
@Setter
@NoArgsConstructor
public class Invoice extends TenantAwareEntity {

    @Column(name = "invoice_number", nullable = false, length = 32, updatable = false)
    private String invoiceNumber;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    /** Optional: a firm may bill a client for work that is not on a single matter. */
    @Column(name = "case_id")
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InvoiceStatus status;

    /** Null only while DRAFT; the database asserts that in {@code ck_invoices_issued_dates}. */
    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal = Money.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal taxAmount = Money.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount = Money.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = Money.ZERO;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Delivery state, on its own axis from {@link #status} — see {@link InvoiceEmailStatus}.
     * The three fields below move together through {@link #recordEmailAttempt} and the
     * database asserts the same pairing in {@code ck_invoices_email_attempt}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", nullable = false, length = 32)
    private InvoiceEmailStatus emailStatus = InvoiceEmailStatus.NOT_SENT;

    /** The address the last attempt was addressed to, as it was at that moment. */
    @Column(name = "email_recipient", length = 255)
    private String emailRecipient;

    @Column(name = "email_last_attempt_at")
    private Instant emailLastAttemptAt;

    /**
     * Ordered, cascading and orphan-removing, because replacing a draft's lines is one
     * operation on the invoice rather than a separate repository the service has to
     * remember to clean up after.
     */
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    public void addLineItem(InvoiceLineItem item) {
        item.setInvoice(this);
        lineItems.add(item);
    }

    public void clearLineItems() {
        lineItems.clear();
    }

    /**
     * The only way an invoice changes status.
     *
     * <p>On the entity so the invariant travels with the object, and so the two timestamps
     * that shadow the status cannot drift from it — the database asserts the same pairing
     * in {@code ck_invoices_paid_at} and {@code ck_invoices_cancelled_at}, so a mistake
     * here is a failed write rather than a row that contradicts itself.
     */
    /**
     * Records the outcome of one attempt to email this invoice.
     *
     * <p>On the entity for the reason {@link #transitionTo} is: the three delivery fields
     * are one fact, and a caller that set the status without the timestamp — or wrote a
     * recipient onto an invoice nobody had tried to email — would produce a row that
     * contradicts itself. {@code ck_invoices_email_attempt} refuses such a row, so a
     * mistake here is a failed write rather than a lie in the database.
     */
    public void recordEmailAttempt(InvoiceEmailStatus outcome, String recipient, Instant when) {
        if (outcome == null || outcome == InvoiceEmailStatus.NOT_SENT) {
            throw new IllegalArgumentException(
                    "An attempt ends in SENT or FAILED; NOT_SENT is the absence of one");
        }
        this.emailStatus = outcome;
        this.emailRecipient = recipient;
        this.emailLastAttemptAt = when;
    }

    public void transitionTo(InvoiceStatus target, Instant when) {
        InvoiceStatusPolicy.requireTransition(this.status, target);
        this.status = target;
        this.paidAt = target == InvoiceStatus.PAID ? when : null;
        this.cancelledAt = target == InvoiceStatus.CANCELLED ? when : null;
    }
}
