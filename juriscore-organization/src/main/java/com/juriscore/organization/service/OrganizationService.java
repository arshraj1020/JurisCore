package com.juriscore.organization.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.util.Slugs;
import com.juriscore.organization.api.dto.UpdateOrganizationRequest;
import com.juriscore.organization.domain.Organization;
import com.juriscore.organization.domain.OrganizationStatus;
import com.juriscore.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);
    private static final int MAX_SLUG_ATTEMPTS = 50;

    private final OrganizationRepository repository;

    /**
     * Provisions a new firm. Called from the self-serve registration flow, which
     * creates the firm and its first FIRM_ADMIN in one transaction.
     */
    @Transactional
    public Organization create(String name, String contactEmail, String timezone) {
        String slug = uniqueSlug(name);
        Organization organization = Organization.builder()
                .name(name.trim())
                .slug(slug)
                .status(OrganizationStatus.ACTIVE)
                .contactEmail(contactEmail)
                .timezone(timezone == null || timezone.isBlank() ? "Asia/Kolkata" : timezone)
                .build();
        Organization saved = repository.save(organization);
        log.info("Provisioned organization {} ({})", saved.getSlug(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Organization getById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.ORGANIZATION_NOT_FOUND, id));
    }

    @Transactional(readOnly = true)
    public Organization getBySlug(String slug) {
        return repository.findBySlug(slug)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.ORGANIZATION_NOT_FOUND, slug));
    }

    @Transactional
    public Organization update(UUID id, UpdateOrganizationRequest request) {
        Organization organization = getById(id);
        organization.setName(request.name().trim());
        organization.setContactEmail(request.contactEmail());
        organization.setContactPhone(request.contactPhone());
        organization.setAddressLine1(request.addressLine1());
        organization.setAddressLine2(request.addressLine2());
        organization.setCity(request.city());
        organization.setState(request.state());
        organization.setCountry(request.country());
        organization.setPostalCode(request.postalCode());
        organization.setRegistrationNumber(request.registrationNumber());
        if (request.timezone() != null && !request.timezone().isBlank()) {
            organization.setTimezone(request.timezone());
        }
        return organization;
    }

    /**
     * Derives a handle from the firm name, appending a counter on collision.
     * The unique index on {@code slug} remains the real arbiter under concurrency;
     * this loop just keeps the common case pretty.
     */
    private String uniqueSlug(String name) {
        String base = Slugs.of(name);
        if (base.isEmpty()) {
            base = "firm";
        }
        if (!repository.existsBySlug(base)) {
            return base;
        }
        for (int attempt = 2; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String candidate = base + "-" + attempt;
            if (!repository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        String fallback = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        if (repository.existsBySlug(fallback)) {
            throw new ApiException(ErrorCode.ORGANIZATION_SLUG_TAKEN);
        }
        return fallback;
    }
}
