package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CreateHearingRequest;
import com.juriscore.casemanagement.api.dto.UpdateHearingRequest;
import com.juriscore.casemanagement.domain.Court;
import com.juriscore.casemanagement.domain.Hearing;
import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.casemanagement.event.HearingScheduledEvent;
import com.juriscore.casemanagement.event.HearingStatusChangedEvent;
import com.juriscore.casemanagement.repository.HearingRepository;
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
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Listings: scheduling them, editing them, and moving them through their lifecycle. */
@Service
@RequiredArgsConstructor
public class HearingService {

    private static final Logger log = LoggerFactory.getLogger(HearingService.class);

    /**
     * Which timeline entry each outcome writes. A map rather than a switch so that adding
     * a status without deciding what the matter's history should say is a missing key at
     * the point of use, not a silently absent entry.
     */
    private static final Map<HearingStatus, CaseEventType> TIMELINE_ENTRY =
            new EnumMap<>(HearingStatus.class);

    static {
        TIMELINE_ENTRY.put(HearingStatus.SCHEDULED, CaseEventType.HEARING_SCHEDULED);
        TIMELINE_ENTRY.put(HearingStatus.COMPLETED, CaseEventType.HEARING_COMPLETED);
        TIMELINE_ENTRY.put(HearingStatus.ADJOURNED, CaseEventType.HEARING_ADJOURNED);
        TIMELINE_ENTRY.put(HearingStatus.CANCELLED, CaseEventType.HEARING_CANCELLED);
    }

    private final HearingRepository hearingRepository;
    private final CourtService courtService;
    private final CaseTimelineRecorder recorder;
    private final EventPublisher eventPublisher;

    /**
     * Lists a matter before a court.
     *
     * <p>Both references are resolved through their owning services before anything is
     * written, so a matter or a court belonging to another firm stops the request with
     * the same not-found a nonexistent one produces. The hearing row, its timeline entry
     * and nothing else are one transaction; {@code hearing.scheduled} is published into
     * it and delivered only after it commits.
     */
    @Transactional
    public Hearing schedule(UUID organizationId, CreateHearingRequest request) {
        LegalCase legalCase = recorder.requireCase(request.caseId(), organizationId);
        Court court = courtService.requireSelectable(request.courtId(), organizationId);

        Hearing hearing = new Hearing();
        hearing.setOrganizationId(organizationId);
        hearing.setCaseId(legalCase.getId());
        hearing.setCourtId(court.getId());
        hearing.setStatus(HearingStatus.SCHEDULED);
        applyDetails(hearing, request.hearingType(), request.scheduledAt(), request.durationMinutes(),
                request.judgeName(), request.courtroom(), request.purpose());

        Hearing saved = hearingRepository.save(hearing);
        recorder.append(legalCase, CaseEventType.HEARING_SCHEDULED,
                "Hearing listed before " + court.getName() + " on " + saved.getScheduledAt());

        log.info("Hearing {} scheduled for case {} in organization {}", saved.getId(),
                legalCase.getId(), organizationId);
        eventPublisher.publish(new HearingScheduledEvent(organizationId, saved.getId(),
                legalCase.getId(), court.getId(), saved.getScheduledAt()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Hearing getScoped(UUID hearingId, UUID organizationId) {
        Hearing hearing = hearingRepository.findByIdAndOrganizationId(hearingId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.HEARING_NOT_FOUND, hearingId));
        TenantGuard.check(hearing, ErrorCode.HEARING_NOT_FOUND);
        return hearing;
    }

    /**
     * The cause list, filtered.
     *
     * <p>Branching over derived queries rather than one query with optional parameters,
     * for the reason {@code HearingRepository} documents. A date range wins over the
     * other filters when both are given, because "what is listed this week" is the
     * question a firm actually asks.
     */
    @Transactional(readOnly = true)
    public Page<Hearing> list(UUID organizationId, UUID caseId, UUID courtId, HearingStatus status,
                              Instant from, Instant to, Pageable pageable) {
        if (from != null || to != null) {
            if (from == null || to == null) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                        "A date range needs both 'from' and 'to'");
            }
            if (!from.isBefore(to)) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "'from' must be before 'to'");
            }
            return hearingRepository.findScheduledBetween(organizationId, from, to, pageable);
        }
        if (caseId != null && status != null) {
            return hearingRepository.findByOrganizationIdAndCaseIdAndStatus(
                    organizationId, caseId, status, pageable);
        }
        if (caseId != null) {
            return hearingRepository.findByOrganizationIdAndCaseId(organizationId, caseId, pageable);
        }
        if (courtId != null) {
            return hearingRepository.findByOrganizationIdAndCourtId(organizationId, courtId, pageable);
        }
        if (status != null) {
            return hearingRepository.findByOrganizationIdAndStatus(organizationId, status, pageable);
        }
        return hearingRepository.findByOrganizationId(organizationId, pageable);
    }

    /**
     * Edits a listing's details. Never its status, and never which matter it belongs to.
     *
     * <p>The court may move — a matter gets transferred — but the case cannot: a hearing
     * that changed matters would leave a timeline entry on one case describing something
     * that now belongs to another.
     */
    @Transactional
    public Hearing update(UUID hearingId, UUID organizationId, UpdateHearingRequest request) {
        Hearing hearing = getScoped(hearingId, organizationId);
        OptimisticVersion.require(hearing, request.version());

        Court court = courtService.requireSelectable(request.courtId(), organizationId);
        hearing.setCourtId(court.getId());
        applyDetails(hearing, request.hearingType(), request.scheduledAt(), request.durationMinutes(),
                request.judgeName(), request.courtroom(), request.purpose());
        hearing.setOutcome(request.outcome());
        return hearing;
    }

    /**
     * The only door into the hearing lifecycle.
     *
     * <p>The rules live on {@code Hearing#transitionTo}, so a second caller cannot reach
     * a forbidden state by finding another route. Every move writes the matching entry
     * onto the matter's timeline in the same transaction, so a refused transition leaves
     * no trace and notifies nobody.
     */
    @Transactional
    public Hearing changeStatus(UUID hearingId, UUID organizationId, HearingStatus target,
                                String outcome) {
        Hearing hearing = getScoped(hearingId, organizationId);
        HearingStatus previous = hearing.getStatus();

        hearing.transitionTo(target);
        if (outcome != null && !outcome.isBlank()) {
            hearing.setOutcome(outcome.trim());
        }

        LegalCase legalCase = recorder.requireCase(hearing.getCaseId(), organizationId);
        recorder.append(legalCase, TIMELINE_ENTRY.get(target),
                "Hearing " + previous + " to " + target
                        + (hearing.getOutcome() == null ? "" : ": " + hearing.getOutcome()));

        log.info("Hearing {} moved from {} to {}", hearingId, previous, target);
        eventPublisher.publish(new HearingStatusChangedEvent(organizationId, hearingId,
                hearing.getCaseId(), previous, target));
        return hearing;
    }

    private void applyDetails(Hearing hearing, com.juriscore.casemanagement.domain.HearingType type,
                              Instant scheduledAt, Integer durationMinutes, String judgeName,
                              String courtroom, String purpose) {
        hearing.setHearingType(type);
        hearing.setScheduledAt(scheduledAt);
        hearing.setDurationMinutes(durationMinutes);
        hearing.setJudgeName(judgeName);
        hearing.setCourtroom(courtroom);
        hearing.setPurpose(purpose);
    }
}
