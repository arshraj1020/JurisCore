package com.juriscore.casework.service;

import com.juriscore.casework.api.dto.CreateCaseRequest;
import com.juriscore.casework.api.dto.UpdateCaseRequest;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.event.CaseCreatedEvent;
import com.juriscore.casework.event.CaseStatusChangedEvent;
import com.juriscore.casework.repository.CaseRepository;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Matters: opening them, editing them, and moving them through their lifecycle. */
@Service
@RequiredArgsConstructor
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    private final CaseRepository caseRepository;
    private final CaseAccess caseAccess;
    private final ClientService clientService;
    private final CaseNumberGenerator caseNumberGenerator;
    private final CaseTimelineService timeline;
    private final EventPublisher eventPublisher;

    /**
     * Opens a matter for an existing, live client of the caller's own firm.
     *
     * <p>All four ways this can fail — no such client, another firm's client, a
     * soft-deleted client, and an id that is simply wrong — answer the same
     * {@code CLIENT_NOT_FOUND}. Distinguishing them would tell a caller which client ids
     * exist in other firms.
     *
     * <p>The case row, its number and its first timeline entry are one transaction. The
     * {@code case.created} event is published into it but delivered only after it
     * commits, so nothing downstream ever hears about a matter that was rolled back.
     */
    @Transactional
    public LegalCase create(UUID organizationId, UUID actorUserId, CreateCaseRequest request) {
        Client client = clientService.requireSelectable(request.clientId(), organizationId);

        Instant now = Instant.now();
        LegalCase legalCase = new LegalCase();
        legalCase.setOrganizationId(organizationId);
        legalCase.setCaseNumber(caseNumberGenerator.nextFor(organizationId, now));
        legalCase.setTitle(request.title().trim());
        legalCase.setDescription(request.description());
        legalCase.setClientId(client.getId());
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(now);

        LegalCase saved = caseRepository.save(legalCase);
        timeline.append(saved, CaseEventType.CASE_CREATED, actorUserId,
                "Case " + saved.getCaseNumber() + " opened for " + client.getDisplayName());

        log.info("Case {} ({}) opened in organization {}", saved.getId(), saved.getCaseNumber(),
                organizationId);
        eventPublisher.publish(new CaseCreatedEvent(organizationId, saved.getId(),
                saved.getCaseNumber(), client.getId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public LegalCase getScoped(UUID caseId, UUID organizationId) {
        return caseAccess.require(caseId, organizationId);
    }

    /**
     * Cases are firm-wide: any member of staff sees every matter their firm holds, and
     * an unassigned lawyer is not treated differently from an assigned one. Whether that
     * is right is a product question; it is not something this method should decide
     * quietly.
     */
    @Transactional(readOnly = true)
    public Page<LegalCase> list(UUID organizationId, CaseStatus status, UUID clientId,
                                String search, Pageable pageable) {
        if (search != null && !search.isBlank()) {
            return caseRepository.search(organizationId, search.trim(), pageable);
        }
        if (status != null && clientId != null) {
            return caseRepository.findByOrganizationIdAndStatusAndClientId(
                    organizationId, status, clientId, pageable);
        }
        if (status != null) {
            return caseRepository.findByOrganizationIdAndStatus(organizationId, status, pageable);
        }
        if (clientId != null) {
            return caseRepository.findByOrganizationIdAndClientId(organizationId, clientId, pageable);
        }
        return caseRepository.findByOrganizationId(organizationId, pageable);
    }

    /**
     * Edits the matter's own details. Never its status.
     *
     * <p>The caller sends the {@code version} it last read. A mismatch means somebody
     * else saved in between, and the answer is 409 {@code CONCURRENT_MODIFICATION}
     * rather than an overwrite — which is the whole point of the version column, and
     * unreachable over HTTP unless the client can supply what it saw.
     */
    @Transactional
    public LegalCase update(UUID caseId, UUID organizationId, UpdateCaseRequest request) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        if (request.version().longValue() != legalCase.getVersion()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Client client = clientService.requireSelectable(request.clientId(), organizationId);
        legalCase.setTitle(request.title().trim());
        legalCase.setDescription(request.description());
        legalCase.setClientId(client.getId());
        return legalCase;
    }

    /**
     * The only door into the lifecycle.
     *
     * <p>The rules live on {@link LegalCase#transitionTo}, so a second caller cannot
     * reach a forbidden state by going round this method. {@code CLOSED} has no exits.
     */
    @Transactional
    public LegalCase changeStatus(UUID caseId, UUID organizationId, UUID actorUserId, CaseStatus target) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        CaseStatus previous = legalCase.getStatus();

        legalCase.transitionTo(target, Instant.now());
        timeline.append(legalCase, CaseEventType.CASE_STATUS_CHANGED, actorUserId,
                "Status changed from " + previous + " to " + target);

        log.info("Case {} moved from {} to {}", caseId, previous, target);
        eventPublisher.publish(new CaseStatusChangedEvent(organizationId, legalCase.getId(),
                legalCase.getCaseNumber(), previous, target));
        return legalCase;
    }
}
