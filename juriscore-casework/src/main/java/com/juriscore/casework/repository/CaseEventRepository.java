package com.juriscore.casework.repository;

import com.juriscore.casework.domain.CaseEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Reads, plus the {@code save} every repository inherits.
 *
 * <p>{@code JpaRepository} does hand this interface {@code delete} and {@code deleteById}
 * — being precise about that matters, because "append-only" is not something the type
 * system is enforcing here. What enforces it is that nothing calls them: no method on
 * {@code CaseTimelineService} mutates or removes an entry, and no controller maps PUT,
 * PATCH or DELETE onto one. A future change that adds such a method is the thing to
 * catch in review, and {@code CaseTimelineIT} asserts the HTTP surface stays closed.
 */
public interface CaseEventRepository extends JpaRepository<CaseEvent, UUID> {

    Page<CaseEvent> findByOrganizationIdAndCaseIdOrderByOccurredAtDescIdDesc(UUID organizationId,
                                                                             UUID caseId,
                                                                             Pageable pageable);
}
