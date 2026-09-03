package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CourtRequest;
import com.juriscore.casemanagement.domain.Court;
import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.casemanagement.event.CourtCreatedEvent;
import com.juriscore.casemanagement.repository.CourtRepository;
import com.juriscore.casemanagement.repository.HearingRepository;
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

import java.util.UUID;

/** The benches a firm appears before. */
@Service
@RequiredArgsConstructor
public class CourtService {

    private static final Logger log = LoggerFactory.getLogger(CourtService.class);

    private final CourtRepository courtRepository;
    private final HearingRepository hearingRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public Court create(UUID organizationId, CourtRequest request) {
        String name = request.name().trim();
        if (courtRepository.existsByOrganizationIdAndNameIgnoreCaseAndActiveTrue(organizationId, name)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "A court with this name is already on your firm's list");
        }

        Court court = new Court();
        court.setOrganizationId(organizationId);
        apply(court, request, name);
        court.setActive(true);

        Court saved = courtRepository.save(court);
        log.info("Court {} added to organization {}", saved.getId(), organizationId);
        eventPublisher.publish(new CourtCreatedEvent(organizationId, saved.getId(), saved.getName()));
        return saved;
    }

    /** Any court of this firm, retired ones included — an old hearing still names one. */
    @Transactional(readOnly = true)
    public Court getScoped(UUID courtId, UUID organizationId) {
        Court court = courtRepository.findByIdAndOrganizationId(courtId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RESOURCE_NOT_FOUND, courtId));
        TenantGuard.check(court, ErrorCode.RESOURCE_NOT_FOUND);
        return court;
    }

    /**
     * A court that can still be used — edited, retired, or listed against.
     *
     * <p>A retired court answers the same not-found a court of another firm does, which
     * is what makes "cannot be chosen for a new hearing" a rule rather than a convention.
     */
    @Transactional(readOnly = true)
    public Court requireSelectable(UUID courtId, UUID organizationId) {
        Court court = courtRepository.findByIdAndOrganizationIdAndActiveTrue(courtId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RESOURCE_NOT_FOUND, courtId));
        TenantGuard.check(court, ErrorCode.RESOURCE_NOT_FOUND);
        return court;
    }

    @Transactional(readOnly = true)
    public Page<Court> list(UUID organizationId, boolean includeRetired, Pageable pageable) {
        return includeRetired
                ? courtRepository.findByOrganizationId(organizationId, pageable)
                : courtRepository.findByOrganizationIdAndActiveTrue(organizationId, pageable);
    }

    @Transactional
    public Court update(UUID courtId, UUID organizationId, CourtRequest request) {
        Court court = requireSelectable(courtId, organizationId);
        OptimisticVersion.require(court, request.version());

        String name = request.name().trim();
        if (!name.equalsIgnoreCase(court.getName())
                && courtRepository.existsByOrganizationIdAndNameIgnoreCaseAndActiveTrue(organizationId, name)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "A court with this name is already on your firm's list");
        }
        apply(court, request, name);
        return court;
    }

    /**
     * Retires a court instead of deleting it.
     *
     * <p>Physical deletion is not available and the foreign key from
     * {@code hearings.court_id} is why: a matter heard before this bench in 2019 has to
     * keep naming it. Retirement hides the court from lists and stops it being chosen for
     * anything new, which is the same shape casework uses for a client.
     *
     * <p>The one refusal is a court that still has listings ahead of it. Those hearings
     * would keep pointing at something the firm has said it no longer uses, and the fix —
     * move or cancel them first — is a decision for a person, not a side effect.
     */
    @Transactional
    public Court retire(UUID courtId, UUID organizationId) {
        Court court = requireSelectable(courtId, organizationId);
        if (hearingRepository.existsByOrganizationIdAndCourtIdAndStatus(
                organizationId, courtId, HearingStatus.SCHEDULED)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "This court still has scheduled hearings. Move or cancel them before retiring it.");
        }
        court.setActive(false);
        log.info("Court {} retired in organization {}", courtId, organizationId);
        return court;
    }

    private void apply(Court court, CourtRequest request, String name) {
        court.setName(name);
        court.setCourtType(request.courtType());
        court.setAddressLine1(request.addressLine1());
        court.setAddressLine2(request.addressLine2());
        court.setCity(request.city());
        court.setState(request.state());
        court.setCountry(request.country());
        court.setTimezone(normalise(request.timezone()));
    }

    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
