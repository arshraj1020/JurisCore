package com.juriscore.casework.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Issues {@code CASE-2026-000001}: unique within a firm, and correct when two people
 * open a matter at the same moment.
 *
 * <h2>Why not check-then-insert</h2>
 *
 * <p>"Read the highest number, add one, insert" is wrong under concurrency and wrong in
 * a way that only shows up in production: two transactions read the same maximum, both
 * build the same number, and one of them fails on the unique index — or, without the
 * index, both succeed and the firm has two matters called {@code CASE-2026-000042}.
 *
 * <h2>What this does instead</h2>
 *
 * <p>A per-firm, per-year counter row, incremented under a row lock:
 *
 * <ol>
 *   <li>{@code INSERT … ON CONFLICT DO NOTHING} makes sure the row exists. Two
 *       concurrent callers both try; one waits on the other's uniqueness check and then
 *       does nothing. Neither fails.</li>
 *   <li>{@code SELECT … FOR UPDATE} takes the row lock and reads the current value. A
 *       second transaction blocks here until the first commits, so no two callers ever
 *       see the same number.</li>
 *   <li>{@code UPDATE} stores the new value. It is released on commit, together with the
 *       case row it numbered — so a rolled-back creation does not burn a number.</li>
 * </ol>
 *
 * <p>Three plain statements, no {@code RETURNING}: {@code executeUpdate} and a bare
 * {@code SELECT} behave identically on every driver, which matters for something on the
 * critical path of every case creation.
 *
 * <p>{@code uk_cases_number} in V2 is still the final arbiter. This method makes the
 * conflict impossible; the index makes it impossible to be wrong about that.
 */
@Component
public class CaseNumberGenerator {

    private static final String PREFIX = "CASE";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @throws IllegalStateException if called outside a transaction — the row lock is
     *                               only worth anything while one is open, so this is
     *                               {@code MANDATORY} rather than {@code REQUIRED}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextFor(UUID organizationId, Instant openedAt) {
        int year = openedAt.atZone(ZoneOffset.UTC).getYear();

        entityManager.createNativeQuery("""
                        INSERT INTO casework.case_number_sequences (organization_id, year, next_value)
                        VALUES (:organizationId, :year, 0)
                        ON CONFLICT (organization_id, year) DO NOTHING
                        """)
                .setParameter("organizationId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        Number current = (Number) entityManager.createNativeQuery("""
                        SELECT next_value FROM casework.case_number_sequences
                        WHERE organization_id = :organizationId AND year = :year
                        FOR UPDATE
                        """)
                .setParameter("organizationId", organizationId)
                .setParameter("year", year)
                .getSingleResult();

        long next = current.longValue() + 1;

        entityManager.createNativeQuery("""
                        UPDATE casework.case_number_sequences SET next_value = :nextValue
                        WHERE organization_id = :organizationId AND year = :year
                        """)
                .setParameter("nextValue", next)
                .setParameter("organizationId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        return format(year, next);
    }

    static String format(int year, long sequence) {
        return String.format("%s-%d-%06d", PREFIX, year, sequence);
    }
}
