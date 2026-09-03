package com.juriscore.casework.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The only class in casework that knows identity exists.
 *
 * <p>It calls {@code UserService}, never {@code UserRepository}: modules talk through
 * service APIs so that moving one behind a network call later is a packaging change.
 * {@code getScoped} already carries the tenant predicate and already answers 404 for a
 * user in another firm, which is exactly the non-disclosure this needs.
 */
@Component
@RequiredArgsConstructor
public class IdentityLawyerDirectory implements LawyerDirectory {

    private final UserService userService;

    @Override
    public void requireAssignableLawyer(UUID userId, UUID organizationId) {
        User user = userService.getScoped(userId, organizationId);

        if (user.getRole() != Role.LAWYER) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "Only users with the LAWYER role can be assigned to a case");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "Only active users can be assigned to a case");
        }
    }
}
