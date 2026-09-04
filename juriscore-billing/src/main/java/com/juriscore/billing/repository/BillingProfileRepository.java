package com.juriscore.billing.repository;

import com.juriscore.billing.domain.BillingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** One profile per firm, so the only lookup there is takes the tenant. */
public interface BillingProfileRepository extends JpaRepository<BillingProfile, UUID> {

    Optional<BillingProfile> findByOrganizationId(UUID organizationId);
}
