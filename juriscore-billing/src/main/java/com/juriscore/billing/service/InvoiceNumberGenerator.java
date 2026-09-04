package com.juriscore.billing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Issues {@code INV-2026-000001}: unique within a firm, and correct when two people raise
 * an invoice at the same moment.
 *
 * <p>Deliberately the same mechanism as {@code CaseNumberGenerator}, down to the three
 * statements, because the problem is identical and a second, cleverer solution to it
 * would be a second thing to be wrong. "Read the highest number, add one, insert" fails
 * under concurrency in the way that only shows up in production: two transactions read
 * the same maximum, both build the same number, and one fails on the unique index — or,
 * without the index, both succeed and the firm has sent two different clients an invoice
 * called {@code INV-2026-000042}. For a case number that is embarrassing; for an invoice
 * number it is an accounting problem.
 *
 * <ol>
 *   <li>{@code INSERT … ON CONFLICT DO NOTHING} makes sure the counter row exists.</li>
 *   <li>{@code SELECT … FOR UPDATE} takes the row lock and reads the current value, so a
 *       second transaction blocks here until the first commits.</li>
 *   <li>{@code UPDATE} stores the new value, released on commit together with the invoice
 *       it numbered — so a rolled-back creation does not burn a number.</li>
 * </ol>
 *
 * <p>{@code uk_invoices_number} in V5 is still the final arbiter. This method makes the
 * conflict impossible; the index makes it impossible to be wrong about that.
 *
 * <p>The counter is per year, so numbering restarts each January. Numbers are therefore
 * unique per firm per year and, with the year in the string, unique per firm outright.
 */
@Component
public class InvoiceNumberGenerator {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param prefix the firm's configured prefix, already validated by
     *               {@code BillingProfileService} against
     *               {@code ck_billing_profiles_prefix}
     * @throws IllegalStateException if called outside a transaction — the row lock is only
     *                               worth anything while one is open, so this is
     *                               {@code MANDATORY} rather than {@code REQUIRED}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextFor(UUID organizationId, String prefix, LocalDate on) {
        int year = on.getYear();

        entityManager.createNativeQuery("""
                        INSERT INTO billing.invoice_number_sequences (organization_id, year, next_value)
                        VALUES (:organizationId, :year, 0)
                        ON CONFLICT (organization_id, year) DO NOTHING
                        """)
                .setParameter("organizationId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        Number current = (Number) entityManager.createNativeQuery("""
                        SELECT next_value FROM billing.invoice_number_sequences
                        WHERE organization_id = :organizationId AND year = :year
                        FOR UPDATE
                        """)
                .setParameter("organizationId", organizationId)
                .setParameter("year", year)
                .getSingleResult();

        long next = current.longValue() + 1;

        entityManager.createNativeQuery("""
                        UPDATE billing.invoice_number_sequences SET next_value = :nextValue
                        WHERE organization_id = :organizationId AND year = :year
                        """)
                .setParameter("nextValue", next)
                .setParameter("organizationId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        return format(prefix, year, next);
    }

    static String format(String prefix, int year, long sequence) {
        return String.format("%s-%d-%06d", prefix, year, sequence);
    }
}
