package com.juriscore.billing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Takes exclusive ownership of a batch of invoices that have gone past their due date.
 *
 * <p>The same primitive {@code ReminderClaimer} uses, for the same reason and with the same
 * justification: the platform runs as more than one instance, every one of them runs this
 * sweep on the same schedule, and "select the due rows, then update them" has all of them
 * selecting the same rows. For a reminder that means a duplicate; for an invoice it means
 * every partner in the firm being told twice that the same client is late.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} makes the batches disjoint by construction rather than
 * by hoping the schedules do not overlap, and it needs no infrastructure the platform does
 * not already have — no distributed lock in Redis, no scheduler library with its own
 * tables. The lock lives until the transaction commits, which is the same moment the
 * status changes, so there is never a window where a row is claimed but still looks due.
 *
 * <p><strong>Idempotence comes from the predicate, not from a flag.</strong> The claim only
 * matches ISSUED and PARTIALLY_PAID; a second run over the same invoices finds them
 * OVERDUE and returns nothing, so a job rerun publishes nothing and notifies nobody. No
 * "last swept at" column is needed, and none exists — a column like that is a second
 * source of truth about a fact the status already states.
 *
 * <p>{@code MANDATORY}: the claim is only worth anything inside the caller's transaction,
 * because that is what holds the lock.
 */
@Component
public class OverdueInvoiceClaimer {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @return the ids this instance now owns, oldest due date first. Possibly empty; never
     *         overlapping with what a concurrent sweep returns.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public List<UUID> claimOverdue(LocalDate asOf, int batchSize) {
        return entityManager.createNativeQuery("""
                        SELECT id FROM billing.invoices
                         WHERE status IN ('ISSUED', 'PARTIALLY_PAID')
                           AND due_date < :asOf
                         ORDER BY due_date
                         LIMIT :batchSize
                         FOR UPDATE SKIP LOCKED
                        """)
                .setParameter("asOf", asOf)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }
}
