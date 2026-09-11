package com.juriscore.common.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Clock readings at the precision the database can actually keep.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code Instant.now()} on a modern Linux JVM returns nanoseconds. Every timestamp column
 * in this schema is {@code TIMESTAMPTZ}, which PostgreSQL stores as microseconds since the
 * epoch — it has no finer unit. So the last three digits of a nanosecond reading are not
 * stored: PostgreSQL rounds them away on write.
 *
 * <p>That rounding is invisible until something reads the value back. A write-then-respond
 * endpoint serialises the entity from the persistence context, so the caller receives the
 * nanosecond value the JVM produced; the next request loads the row and receives the
 * microsecond value the database kept. The two differ — {@code ...573775668Z} against
 * {@code ...573776Z} — for the same stamp that nobody changed. The API had reported a
 * precision it was never going to honour.
 *
 * <p>Truncating at the point the stamp is taken removes the discrepancy at its source: the
 * value the entity holds, the value the row holds, and the value every response carries are
 * then the same number. Truncation rather than rounding is deliberate — it is idempotent,
 * so passing an already-truncated instant through again cannot shift it, which rounding at
 * a boundary could.
 *
 * <p>This is about representation, not about time. Microsecond resolution is far finer than
 * anything this domain distinguishes; no ordering, comparison or business rule can tell the
 * difference.
 */
public final class Timestamps {

    private Timestamps() {
    }

    /** Now, truncated to the microsecond that {@code TIMESTAMPTZ} will actually store. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The same truncation applied to an instant from elsewhere — an event's {@code
     * occurredAt}, a caller-supplied time — so that a value about to be persisted matches
     * what the column will hold.
     *
     * @return {@code null} if {@code instant} is null, so this can wrap an optional value
     */
    public static Instant storable(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
