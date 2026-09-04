package com.juriscore.billing.domain;

/**
 * Where an invoice is in its life. The transitions between these are in
 * {@link InvoiceStatusPolicy}; this enum only names the states.
 */
public enum InvoiceStatus {

    /** Being written. The only state in which money and identity can still be edited. */
    DRAFT,

    /** Sent to the client. Financially frozen from here on. */
    ISSUED,

    /** Some money has arrived, but not all of it. */
    PARTIALLY_PAID,

    /** Settled in full. Terminal. */
    PAID,

    /** Issued, unpaid or part-paid, and past its due date. */
    OVERDUE,

    /** Withdrawn. Terminal, and never payable. */
    CANCELLED;

    /** Whether money may still be recorded against an invoice in this state. */
    public boolean acceptsPayment() {
        return this == ISSUED || this == PARTIALLY_PAID || this == OVERDUE;
    }

    /** Whether the figures and the client/case identity may still be edited. */
    public boolean isEditable() {
        return this == DRAFT;
    }

    public boolean isTerminal() {
        return this == PAID || this == CANCELLED;
    }
}
