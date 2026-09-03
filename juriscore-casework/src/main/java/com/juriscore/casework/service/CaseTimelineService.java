package com.juriscore.casework.service;

import com.juriscore.casework.api.dto.AddTimelineNoteRequest;
import com.juriscore.casework.domain.CaseEvent;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.repository.CaseEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The case timeline: what happened to a matter, in the order it happened.
 *
 * <p>Append-only, and the class is written so that staying that way costs nothing —
 * there is no update method, no delete method, and no controller mapping that could
 * call one. The one entry a person writes directly, {@link CaseEventType#MANUAL_NOTE},
 * is no more editable than the rest.
 *
 * <p>Entries are written inside the transaction that caused them, which is the opposite
 * of how this module publishes domain events. Both are deliberate: a rolled-back case
 * creation must leave no timeline entry, and must also notify nobody.
 */
@Service
@RequiredArgsConstructor
public class CaseTimelineService {

    private final CaseAccess caseAccess;
    private final CaseEventRepository caseEventRepository;

    /**
     * Records something that happened to a matter.
     *
     * <p>{@code MANDATORY}: an entry only makes sense as part of the change it describes,
     * so it must join the caller's transaction rather than quietly opening its own and
     * surviving a rollback.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CaseEvent append(LegalCase legalCase, CaseEventType eventType, UUID actorUserId, String summary) {
        CaseEvent event = new CaseEvent();
        event.setOrganizationId(legalCase.getOrganizationId());
        event.setCaseId(legalCase.getId());
        event.setEventType(eventType);
        event.setActorUserId(actorUserId);
        event.setOccurredAt(Instant.now());
        event.setSummary(summary);
        return caseEventRepository.save(event);
    }

    /**
     * Newest first, with the id as a tiebreak.
     *
     * <p>The tiebreak is load-bearing: several entries written in one transaction can
     * share {@code occurred_at}, and a sort that cannot separate them makes the boundary
     * between two pages undefined — the same row shown twice, or skipped.
     */
    @Transactional(readOnly = true)
    public Page<CaseEvent> list(UUID caseId, UUID organizationId, Pageable pageable) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        return caseEventRepository.findByOrganizationIdAndCaseIdOrderByOccurredAtDescIdDesc(
                organizationId, legalCase.getId(), pageable);
    }

    @Transactional
    public CaseEvent addNote(UUID caseId, UUID organizationId, UUID actorUserId,
                             AddTimelineNoteRequest request) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        return append(legalCase, CaseEventType.MANUAL_NOTE, actorUserId, request.summary().trim());
    }
}
