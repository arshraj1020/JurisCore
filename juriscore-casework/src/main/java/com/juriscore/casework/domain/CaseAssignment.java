package com.juriscore.casework.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One lawyer working one matter.
 *
 * <p>A row rather than a column on the case, because a matter may be staffed by
 * several lawyers, exactly one of whom is lead. "At most one lead" is a partial
 * unique index in V2; "at least one, once anybody is assigned" is enforced by
 * {@code CaseAssignmentService}, which refuses to unassign the lead unless another
 * assignee is promoted in the same transaction.
 *
 * <p>{@code lawyerUserId} points into {@code identity.users} and therefore has no
 * foreign key. It is validated through identity's service API before the row is
 * written — see {@code LawyerDirectory}.
 */
@Entity
@Table(name = "case_assignments", schema = "casework")
@Getter
@Setter
@NoArgsConstructor
public class CaseAssignment extends TenantAwareEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "lawyer_user_id", nullable = false, updatable = false)
    private UUID lawyerUserId;

    @Column(name = "is_lead", nullable = false)
    private boolean lead;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", updatable = false)
    private UUID assignedBy;
}
