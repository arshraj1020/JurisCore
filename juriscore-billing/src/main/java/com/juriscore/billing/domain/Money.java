package com.juriscore.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Every rounding decision in JurisCore's billing, in one file.
 *
 * <p>Money is {@link BigDecimal} throughout and {@code double} appears nowhere, for the
 * usual reason stated precisely: {@code 0.1 + 0.2} is {@code 0.30000000000000004} in
 * binary floating point, and an invoice is not a place to discover that. The database
 * agrees — every amount column is {@code NUMERIC}, never {@code REAL} or
 * {@code DOUBLE PRECISION}.
 *
 * <h2>Where rounding happens</h2>
 *
 * <p>Twice per line, and nowhere else:
 *
 * <ol>
 *   <li>{@code amount = round(quantity × unitPrice)} — quantity carries three decimals,
 *       so the product genuinely needs rounding.</li>
 *   <li>{@code taxAmount = round(amount × taxRate ÷ 100)} — computed from the
 *       <em>already rounded</em> line amount, so what a reader can verify with a
 *       calculator from the printed figures is what the system stored.</li>
 * </ol>
 *
 * <p>Everything above a line is a sum of values that are already at scale 2, and adding
 * exact two-decimal figures needs no rounding at all. So {@code subtotal},
 * {@code taxAmount} and {@code totalAmount} are exact, and
 * {@code total = subtotal + tax − discount} holds as an identity rather than
 * approximately — which is why {@code ck_invoices_total} can assert it in the database.
 *
 * <p>The consequence worth stating: tax is computed per line and then summed, not
 * computed once on the subtotal. Those differ by up to half a paisa per line, and the
 * per-line figure is the one that appears next to each line on the printed invoice.
 *
 * <h2>Why HALF_UP</h2>
 *
 * <p>It is what a person does by hand and what every invoice a client has ever received
 * appears to do. {@code HALF_EVEN} is better for long statistical runs and worse here:
 * a firm explaining why 2.125 became 2.12 on one line and 2.14 on another is a
 * conversation that should not have to happen.
 */
public final class Money {

    /** Currency amounts carry two decimals. */
    public static final int SCALE = 2;

    /** Quantities carry three, so half an hour and a third of an hour both survive. */
    public static final int QUANTITY_SCALE = 3;

    /** Tax rates are percentages with three decimals: {@code 18.000} means 18%. */
    public static final int RATE_SCALE = 3;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    /** A currency amount at the canonical scale. Null becomes zero. */
    public static BigDecimal amount(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal quantity(BigDecimal value) {
        return value == null ? null : value.setScale(QUANTITY_SCALE, ROUNDING);
    }

    public static BigDecimal rate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(RATE_SCALE) : value.setScale(RATE_SCALE, ROUNDING);
    }

    /** {@code round(quantity × unitPrice)} — the first of the two rounding points. */
    public static BigDecimal lineAmount(BigDecimal quantity, BigDecimal unitPrice) {
        return amount(quantity.multiply(unitPrice));
    }

    /**
     * {@code round(amount × rate ÷ 100)} — the second, and it is taken on the rounded
     * line amount on purpose. See the class comment.
     */
    public static BigDecimal taxOn(BigDecimal lineAmount, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() == 0) {
            return ZERO;
        }
        return amount(lineAmount.multiply(ratePercent).divide(HUNDRED, SCALE + 4, ROUNDING));
    }

    /** Addition of two canonical amounts, which is exact. */
    public static BigDecimal sum(BigDecimal a, BigDecimal b) {
        return amount(a).add(amount(b));
    }

    /** True when the two amounts are numerically equal, whatever their scales. */
    public static boolean equal(BigDecimal a, BigDecimal b) {
        return amount(a).compareTo(amount(b)) == 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }
}
