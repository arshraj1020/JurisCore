package com.juriscore.app.notification;

import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Who, inside a firm, should hear about something.
 *
 * <p>It lives in {@code juriscore-app} rather than in {@code juriscore-notifications}, and
 * that placement is the point. The notifications module knows how to deliver a message to
 * a user id and deliberately knows nothing about identity — so it depends only on
 * {@code juriscore-common}, and its rules are unit-testable with no identity module
 * standing up. Deciding <em>which</em> user ids need both identity and billing in view,
 * and {@code juriscore-app} is the module that already has every module in view because it
 * is the one that assembles them. This is the same shape as
 * {@code IdentityLawyerDirectory}, one layer further out.
 *
 * <p>It goes through {@code UserService}, never through {@code UserRepository}: reaching
 * into another module's repository is the boundary rule this codebase does not break.
 */
@Component
@RequiredArgsConstructor
public class FirmStaffDirectory {

    /**
     * More administrators than any real law firm has. A bound rather than a page size —
     * if a firm ever exceeds it, some administrators would silently stop being notified,
     * which is worth knowing about rather than paging around.
     */
    private static final int MAX_ADMINS = 100;

    private final UserService userService;

    /**
     * The firm's active administrators.
     *
     * <p>Suspended and deactivated accounts are excluded: a notification for somebody who
     * cannot sign in is a row nobody will ever read.
     */
    @Transactional(readOnly = true)
    public List<UUID> activeAdministrators(UUID organizationId) {
        return userService.list(organizationId, Role.FIRM_ADMIN, null,
                        PageRequest.of(0, MAX_ADMINS))
                .getContent().stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
    }
}
