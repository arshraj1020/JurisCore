package com.juriscore.casemanagement.service;

import java.util.UUID;

/**
 * Case management's one question for the identity module: may this user be given work?
 *
 * <p>A port for the same two reasons casework has {@code LawyerDirectory} — identity's
 * types stay at one boundary class, and the assignment rules stay unit-testable without
 * standing up identity.
 *
 * <p>Deliberately a different question from {@code LawyerDirectory}'s. That one asks
 * whether somebody may be put on a case as counsel, which only a LAWYER may be. This one
 * asks whether somebody may be given a task, which any member of the firm's staff may be
 * — a clerk chasing a filing is the ordinary case, not the exception.
 */
public interface FirmMemberDirectory {

    /**
     * Passes silently when {@code userId} is an ACTIVE member of staff in
     * {@code organizationId}.
     *
     * @throws com.juriscore.common.error.ApiException {@code USER_NOT_FOUND} (404) when no
     *         such user exists in that organization — including when they exist in
     *         another one, which must not be distinguishable; or {@code INVALID_ARGUMENT}
     *         (400) when they are in this firm but are not active staff.
     */
    void requireAssignableMember(UUID userId, UUID organizationId);
}
