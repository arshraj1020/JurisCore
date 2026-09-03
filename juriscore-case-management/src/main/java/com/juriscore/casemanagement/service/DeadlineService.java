package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.DeadlineRequest;
import com.juriscore.casemanagement.domain.Deadline;
import com.juriscore.casemanagement.domain.DeadlineStatus;
import com.juriscore.casemanagement.event.DeadlineCompletedEvent;
import com.juriscore.casemanagement.event.DeadlineCreatedEvent;
import com.juriscore.casemanagement.repository.DeadlineRepository;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Dates a matter has to meet. */
@Service
@RequiredArgsConstructor
public class DeadlineService {

    private static final Logger log = LoggerFactory.getLogger(DeadlineService.class);

    private final DeadlineRepository deadlineRepository;
    private final CaseTimelineRecorder recorder;
    private final EventPublisher eventPublisher;

    @Transactional
    public Deadline create(UUID caseId, UUID organizationId, DeadlineRequest request) {
        LegalCase legalCase = recorder.requireCase(caseId, organizationId);

        Deadline deadline = new Deadline();
        deadline.setOrganizationId(organizationId);
        deadline.setCaseId(legalCase.getId());
        deadline.setStatus(DeadlineStatus.OPEN);
        apply(deadline, request);

        Deadline saved = deadlineRepository.save(deadline);
        recorder.append(legalCase, CaseEventType.DEADLINE_CREATED,
                "Deadline set: " + saved.getTitle() + " due " + saved.getDueAt());

        log.info("Deadline {} created on case {} in organization {}", saved.getId(), caseId,
                organizationId);
        eventPublisher.publish(new DeadlineCreatedEvent(organizationId, saved.getId(),
                legalCase.getId(), saved.getTitle(), saved.getDueAt()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Deadline getScoped(UUID deadlineId, UUID organizationId) {
        Deadline deadline = deadlineRepository.findByIdAndOrganizationId(deadlineId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RESOURCE_NOT_FOUND, deadlineId));
        TenantGuard.check(deadline, ErrorCode.RESOURCE_NOT_FOUND);
        return deadline;
    }

    @Transactional(readOnly = true)
    public Deadline requireLive(UUID deadlineId, UUID organizationId) {
        Deadline deadline = deadlineRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(deadlineId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RESOURCE_NOT_FOUND, deadlineId));
        TenantGuard.check(deadline, ErrorCode.RESOURCE_NOT_FOUND);
        return deadline;
    }

    @Transactional(readOnly = true)
    public Page<Deadline> listForCase(UUID caseId, UUID organizationId, DeadlineStatus status,
                                      Pageable pageable) {
        LegalCase legalCase = recorder.requireCase(caseId, organizationId);
        return status == null
                ? deadlineRepository.findByOrganizationIdAndCaseIdAndDeletedAtIsNull(
                        organizationId, legalCase.getId(), pageable)
                : deadlineRepository.findByOrganizationIdAndCaseIdAndStatusAndDeletedAtIsNull(
                        organizationId, legalCase.getId(), status, pageable);
    }

    @Transactional
    public Deadline update(UUID deadlineId, UUID organizationId, DeadlineRequest request) {
        Deadline deadline = requireLive(deadlineId, organizationId);
        OptimisticVersion.require(deadline, request.version());
        apply(deadline, request);
        return deadline;
    }

    @Transactional
    public Deadline changeStatus(UUID deadlineId, UUID organizationId, DeadlineStatus target) {
        Deadline deadline = requireLive(deadlineId, organizationId);
        DeadlineStatus previous = deadline.getStatus();

        deadline.transitionTo(target, Instant.now());

        LegalCase legalCase = recorder.requireCase(deadline.getCaseId(), organizationId);
        CaseEventType entry = target == DeadlineStatus.COMPLETED
                ? CaseEventType.DEADLINE_COMPLETED
                : CaseEventType.DEADLINE_CANCELLED;
        recorder.append(legalCase, entry,
                "Deadline " + target.name().toLowerCase() + ": " + deadline.getTitle());

        if (target == DeadlineStatus.COMPLETED) {
            eventPublisher.publish(new DeadlineCompletedEvent(organizationId, deadlineId,
                    deadline.getCaseId(), deadline.getTitle()));
        }

        log.info("Deadline {} moved from {} to {}", deadlineId, previous, target);
        return deadline;
    }

    /** Soft, for the reason tasks are: the matter's history has to keep resolving. */
    @Transactional
    public Deadline remove(UUID deadlineId, UUID organizationId) {
        Deadline deadline = requireLive(deadlineId, organizationId);
        deadline.markDeleted(Instant.now());
        log.info("Deadline {} removed in organization {}", deadlineId, organizationId);
        return deadline;
    }

    private void apply(Deadline deadline, DeadlineRequest request) {
        deadline.setTitle(request.title().trim());
        deadline.setDescription(request.description());
        deadline.setDeadlineType(request.deadlineType());
        deadline.setDueAt(request.dueAt());
        deadline.setSource(request.source());
    }
}
