package com.juriscore.audit.service;

import com.juriscore.audit.domain.AuditEvent;
import com.juriscore.audit.repository.AuditEventRepository;
import com.juriscore.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The one way anything gets written to the audit trail.
 *
 * <h2>Why it commits on its own</h2>
 *
 * <p>{@code REQUIRES_NEW}. Callers are {@code AFTER_COMMIT} listeners, where the producing
 * transaction is finished and there is nothing left to join. Without its own transaction
 * the write would either fail outright or silently attach to a connection nobody is going
 * to commit — which is the same trap {@code LoginAttemptRecorder} and
 * {@code DocumentFailureRecorder} already document, arrived at from the other direction.
 *
 * <p>It also means an audit failure cannot break the business operation that caused it. A
 * duplicate, a constraint or a database hiccup is logged at ERROR and swallowed: an invoice
 * that was legitimately issued must not be reported as failed because the record of it
 * could not be written. That is a real trade — a lost audit row is a genuine loss — and it
 * is the right one for an in-process trail. A system that must not lose a single audit row
 * writes it inside the business transaction instead, and pays for that with an audit table
 * that can roll back a payment.
 *
 * <h2>Who the actor is</h2>
 *
 * <p>{@code CurrentUser}, read here rather than carried on every event. An
 * {@code AFTER_COMMIT} listener runs synchronously on the request thread, so the security
 * context is still populated and the caller is exactly who it was. Scheduled sweeps have no
 * caller and record none, which is what the nullable column is for.
 */
@Service
@RequiredArgsConstructor
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    /** Set by {@code RequestIdFilter}; lets an audit row line up with its logs. */
    private static final String REQUEST_ID_KEY = "requestId";

    private final AuditEventRepository auditEventRepository;

    /**
     * Records one thing that happened.
     *
     * @param sourceEventId the domain event's id, or null for a direct call. UNIQUE in the
     *                      database, so at-least-once delivery cannot record the same
     *                      business action twice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID organizationId, String action, String entityType, UUID entityId,
                       Instant occurredAt, String summary, UUID sourceEventId) {
        try {
            AuditEvent event = new AuditEvent();
            event.setOrganizationId(organizationId);
            event.setActorUserId(CurrentUser.find().map(user -> user.userId()).orElse(null));
            event.setAction(action);
            event.setEntityType(entityType);
            event.setEntityId(entityId);
            event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
            event.setCreatedAt(Instant.now());
            event.setCreatedBy(event.getActorUserId());
            event.setRequestId(MDC.get(REQUEST_ID_KEY));
            event.setSummary(AuditRedaction.require(summary));
            event.setSourceEventId(sourceEventId);

            auditEventRepository.save(event);
        } catch (DataIntegrityViolationException e) {
            // uk_audit_events_source: another instance recorded the same event first.
            // Exactly what the constraint is for, and not worth a stack trace.
            log.debug("Audit row for event {} already exists", sourceEventId);
        } catch (RuntimeException e) {
            log.error("Failed to record audit event action={} entity={}/{}; the business "
                    + "operation itself succeeded", action, entityType, entityId, e);
        }
    }
}
