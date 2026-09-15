package com.juriscore.billing.domain;

/**
 * Whether a copy of this invoice has reached the client's inbox.
 *
 * <p>Deliberately a second axis, not more values on {@link InvoiceStatus}. Payment state
 * and delivery state answer different questions — a PAID invoice may never have been
 * emailed, an ISSUED one may have been emailed three times — and folding them together
 * would produce a state machine where every payment transition had to be multiplied by
 * every delivery outcome.
 *
 * <p>Only the <em>current</em> state lives on the invoice. The history of who emailed what
 * and when is the audit trail's job, which is where this platform already keeps history;
 * see {@code InvoiceEmailedEvent}.
 */
public enum InvoiceEmailStatus {

    /** Never attempted. The state every invoice is raised in. */
    NOT_SENT,

    /** The provider accepted the message. Acceptance, note, not proof of reading. */
    SENT,

    /**
     * An attempt was made and the provider refused it or could not be reached.
     *
     * <p>Recorded rather than rolled back, because "we tried and it did not go" is a
     * materially different thing for a firm to see than "nobody has tried".
     */
    FAILED
}
