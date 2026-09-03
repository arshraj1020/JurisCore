package com.juriscore.casemanagement.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Takes exclusive ownership of a batch of due reminders.
 *
 * <h2>Why this is not just a query</h2>
 *
 * <p>The platform runs as more than one instance behind a load balancer, and every one of
 * them will run this sweep on the same schedule. "Select the due rows, then update them"
 * has all of them selecting the same rows, and the same reminder is published two, three
 * or five times. For an email that is a duplicate; for anything a consumer bills against
 * it is worse.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} is the primitive that fixes it, and it needs no
 * infrastructure the platform does not already have. Each instance locks the rows it
 * takes and steps over rows another instance has already locked, so the batches are
 * disjoint by construction rather than by hoping the schedules do not overlap. The lock
 * lives until the transaction commits, which is the same moment the status changes to
 * SENT — so there is never a window where a row is claimed but still looks due.
 *
 * <p>The alternative would be a distributed lock in Redis or a scheduler library with its
 * own tables. Both are real dependencies to operate, and neither is more correct than a
 * row lock the database is already keeping for us.
 *
 * <p>{@code MANDATORY}: the claim is only worth anything inside the caller's transaction,
 * because that is what holds the lock.
 */
@Component
public class ReminderClaimer {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @return the ids this instance now owns, oldest first. Possibly empty; never
     *         overlapping with what a concurrent sweep returns.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public List<UUID> claimDue(Instant now, int batchSize) {
        return entityManager.createNativeQuery("""
                        SELECT id FROM case_management.reminders
                         WHERE status = 'SCHEDULED' AND remind_at <= :now
                         ORDER BY remind_at
                         LIMIT :batchSize
                         FOR UPDATE SKIP LOCKED
                        """)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }
}
