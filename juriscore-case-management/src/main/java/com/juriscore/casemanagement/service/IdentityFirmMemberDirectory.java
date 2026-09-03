package com.juriscore.casemanagement.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The only class in case management that knows identity exists.
 *
 * <p>It calls {@code UserService}, never {@code UserRepository}. {@code getScoped}
 * already carries the tenant predicate and already answers 404 for a user in another
 * firm, which is exactly the non-disclosure this needs — a caller must not be able to
 * tell "no such user" from "somebody else's user" by the error they get back.
 */
@Component
@RequiredArgsConstructor
public class IdentityFirmMemberDirectory implements FirmMemberDirectory {

    private final UserService userService;

    @Override
    public void requireAssignableMember(UUID userId, UUID organizationId) {
        User user = userService.getScoped(userId, organizationId);

        if (user.getRole() == null || !user.getRole().isStaff()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "Work can only be assigned to a member of the firm's staff");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "Work can only be assigned to an active user");
        }
    }
}
