package com.juriscore.billing.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The invoice lifecycle, in one place.
 *
 * <p>Kept as data rather than a chain of {@code if}s, exactly as {@code CaseStatusPolicy}
 * is, so the whole rule reads at once and can be asserted cell by cell.
 *
 * <pre>
 *   DRAFT          -> ISSUED, CANCELLED
 *   ISSUED         -> PARTIALLY_PAID, PAID, OVERDUE, CANCELLED
 *   PARTIALLY_PAID -> PAID, OVERDUE, CANCELLED
 *   OVERDUE        -> PARTIALLY_PAID, PAID, CANCELLED
 *   PAID           -> (terminal)
 *   CANCELLED      -> (terminal)
 * </pre>
 *
 * <h2>The edges that are here, and why</h2>
 *
 * <p><strong>{@code DRAFT -> CANCELLED}.</strong> A draft that will never be sent has to
 * be disposable, and there is no delete: an invoice number has already been burned, and
 * a firm that cannot account for a gap in its numbering has a worse problem than a
 * cancelled draft.
 *
 * <p><strong>{@code PARTIALLY_PAID -> CANCELLED}.</strong> The alternative — refusing to
 * withdraw an invoice against which a part payment was recorded — leaves a firm that
 * mistyped an amount with no way out except a second wrong record. Cancelling does not
 * delete the payments; they stay attached and visible.
 *
 * <p><strong>{@code OVERDUE -> PARTIALLY_PAID}.</strong> Money can arrive late, and it
 * usually does. An overdue invoice that receives a part payment is part paid, and the
 * sweep may make it overdue again the next time it runs — the oscillation is the truth
 * about the invoice, not a defect. What it must not do is notify twice, and that is
 * handled by the notification dedupe key rather than by refusing a real transition.
 *
 * <h2>The edges that are not</h2>
 *
 * <p>{@code PAID} and {@code CANCELLED} have no outgoing edges. Unwinding either is a
 * new financial document — a credit note — and Phase 5 does not have one. Quietly
 * allowing {@code PAID -> ISSUED} would let a firm rewrite settled history with a PATCH,
 * which is precisely the thing an audit trail exists to make impossible.
 *
 * <p>{@code DRAFT} cannot be reached from anywhere. Issuing is one-way.
 */
public final class InvoiceStatusPolicy {

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED =
            new EnumMap<>(InvoiceStatus.class);

    static {
        ALLOWED.put(InvoiceStatus.DRAFT,
                Set.of(InvoiceStatus.ISSUED, InvoiceStatus.CANCELLED));
        ALLOWED.put(InvoiceStatus.ISSUED,
                Set.of(InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID, InvoiceStatus.OVERDUE,
                        InvoiceStatus.CANCELLED));
        ALLOWED.put(InvoiceStatus.PARTIALLY_PAID,
                Set.of(InvoiceStatus.PAID, InvoiceStatus.OVERDUE, InvoiceStatus.CANCELLED));
        ALLOWED.put(InvoiceStatus.OVERDUE,
                Set.of(InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID, InvoiceStatus.CANCELLED));
        ALLOWED.put(InvoiceStatus.PAID, Set.of());
        ALLOWED.put(InvoiceStatus.CANCELLED, Set.of());
    }

    private InvoiceStatusPolicy() {
    }

    public static Set<InvoiceStatus> allowedFrom(InvoiceStatus current) {
        return ALLOWED.getOrDefault(current, Set.of());
    }

    public static boolean permits(InvoiceStatus current, InvoiceStatus target) {
        return current != null && target != null && allowedFrom(current).contains(target);
    }

    /**
     * @throws ApiException {@code ILLEGAL_STATE_TRANSITION} (409) for any move the
     *                      lifecycle does not allow — including a move to the status the
     *                      invoice already holds, which is a caller mistake rather than a
     *                      silent success.
     */
    public static void requireTransition(InvoiceStatus current, InvoiceStatus target) {
        if (!permits(current, target)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "An invoice cannot move from " + current + " to " + target);
        }
    }
}
