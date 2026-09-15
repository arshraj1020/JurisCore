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

    /**
     * Whether a copy of an invoice in this state may be emailed to the client.
     *
     * <p>Not a DRAFT: it is a working document, nobody has been asked to pay it, and its
     * figures can still change under the copy the client received. Not CANCELLED either —
     * sending a withdrawn bill is the one outcome worse than sending none.
     *
     * <p>PAID is included on purpose. A settled invoice is still the document a client
     * asks for at year end, and refusing to re-send it would be a rule with no reason
     * behind it.
     */
    public boolean canBeEmailed() {
        return this == ISSUED || this == PARTIALLY_PAID || this == OVERDUE || this == PAID;
    }
}
