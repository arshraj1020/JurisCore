package com.juriscore.casework.service;

import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.repository.CaseRepository;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single way anything in casework gets hold of a matter.
 *
 * <p>It exists so that the tenant check cannot be forgotten in one of the four services
 * that need it, and so those services do not have to depend on each other to share it —
 * a timeline service that called the case service, which calls the timeline service,
 * would be a bean cycle.
 *
 * <p>Two layers, both deliberate. The repository predicate scopes the query; the guard
 * re-checks the row that came back. The second is redundant today and is meant to be:
 * it is the layer that still holds the day somebody adds a lookup without the predicate.
 *
 * <p>A matter belonging to another firm answers {@code CASE_NOT_FOUND}, not
 * {@code ACCESS_DENIED}. A 403 would confirm the case exists, which is itself the
 * disclosure.
 */
@Component
@RequiredArgsConstructor
public class CaseAccess {

    private final CaseRepository caseRepository;

    public LegalCase require(UUID caseId, UUID organizationId) {
        LegalCase legalCase = caseRepository.findByIdAndOrganizationId(caseId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.CASE_NOT_FOUND, caseId));
        TenantGuard.check(legalCase, ErrorCode.CASE_NOT_FOUND);
        return legalCase;
    }
}
