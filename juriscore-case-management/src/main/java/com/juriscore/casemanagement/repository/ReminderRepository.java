package com.juriscore.casemanagement.repository;

import com.juriscore.casemanagement.domain.Reminder;
import com.juriscore.casemanagement.domain.ReminderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    Optional<Reminder> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<Reminder> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<Reminder> findByOrganizationIdAndStatus(UUID organizationId, ReminderStatus status,
                                                  Pageable pageable);

    List<Reminder> findByOrganizationIdAndTaskId(UUID organizationId, UUID taskId);

    List<Reminder> findByOrganizationIdAndDeadlineId(UUID organizationId, UUID deadlineId);

    /**
     * The one query in this module with no organization predicate, and the only place
     * one is defensible: the scheduler is not acting for a caller, it is sweeping the
     * whole table on the platform's behalf, and a tenant filter would mean either
     * iterating every firm or picking one arbitrarily. Nothing it reads leaves the
     * process except as a domain event that carries its own {@code organizationId}, and
     * it is unreachable from any request path — {@code ReminderDispatchService} is the
     * only caller and no controller touches it.
     */
    List<Reminder> findByIdIn(List<UUID> ids);
}
