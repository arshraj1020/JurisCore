package com.juriscore.audit.service;

import com.juriscore.audit.domain.AuditEvent;
import com.juriscore.audit.repository.AuditEventRepository;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reading the audit trail.
 *
 * <p>Read-only in the strongest sense available: the repository it depends on has no write
 * method other than {@code append}, and this class does not call it.
 *
 * <p>{@code organizationId} is a required argument on every method and comes from
 * {@code CurrentUser} at the controller, never from a query parameter. There is no way to
 * ask for another firm's rows, and no way to ask for all firms' rows.
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditEventRepository auditEventRepository;

    /**
     * The firm's audit trail, most recent first, with every filter optional.
     *
     * <p>{@code from} and {@code to} are inclusive, which is what a person filling in "from
     * the 1st to the 31st" means.
     */
    @Transactional(readOnly = true)
    public Page<AuditEvent> search(UUID organizationId, UUID actorUserId, String action,
                                   String entityType, UUID entityId, Instant from, Instant to,
                                   Pageable pageable) {
        // Most recent first with the id as a tiebreak, applied here rather than left to the
        // caller's Pageable: several rows written by one request share an instant, and a
        // trail that pages unstably is a trail somebody will read twice and trust once.
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        return auditEventRepository.findAll(
                AuditSpecifications.matching(organizationId, actorUserId, blankToNull(action),
                        blankToNull(entityType), entityId, from, to),
                ordered);
    }

    /** One row of this firm's trail. Another firm's answers not-found, as everywhere else. */
    @Transactional(readOnly = true)
    public AuditEvent require(UUID auditEventId, UUID organizationId) {
        AuditEvent event = auditEventRepository
                .findByIdAndOrganizationId(auditEventId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RESOURCE_NOT_FOUND, auditEventId));
        TenantGuard.check(event.getOrganizationId(), ErrorCode.RESOURCE_NOT_FOUND);
        return event;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
