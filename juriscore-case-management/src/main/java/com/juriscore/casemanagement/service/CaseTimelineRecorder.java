package com.juriscore.casemanagement.service;

import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.service.CaseAccess;
import com.juriscore.casework.service.CaseTimelineService;
import com.juriscore.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single seam between case management and the matter it hangs off.
 *
 * <p>Two jobs, both of which every service in this module needs and none of which any of
 * them should re-implement: resolve a case id to a case the caller is actually entitled
 * to see, and write an entry onto that case's timeline.
 *
 * <p>Both go through casework's service API — {@code CaseAccess} and
 * {@code CaseTimelineService} — never its repositories, per ARCHITECTURE.md §1. The
 * timeline in particular is Phase 2's, not a second one: a matter has one history.
 */
@Component
@RequiredArgsConstructor
public class CaseTimelineRecorder {

    private final CaseAccess caseAccess;
    private final CaseTimelineService timeline;

    /**
     * The matter, or {@code CASE_NOT_FOUND}.
     *
     * <p>Delegates rather than querying: {@code CaseAccess} already applies the tenant
     * predicate and the guard, and already answers 404 rather than 403 for another firm's
     * matter. Reimplementing that here would be a second chance to get it wrong.
     */
    public LegalCase requireCase(UUID caseId, UUID organizationId) {
        return caseAccess.require(caseId, organizationId);
    }

    /**
     * Records something on the matter's timeline.
     *
     * <p>{@code MANDATORY}: an entry only means anything as part of the change it
     * describes, so it joins the caller's transaction rather than opening its own and
     * outliving a rollback.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(LegalCase legalCase, CaseEventType eventType, String summary) {
        timeline.append(legalCase, eventType, CurrentUser.requireUserId(), summary);
    }
}
