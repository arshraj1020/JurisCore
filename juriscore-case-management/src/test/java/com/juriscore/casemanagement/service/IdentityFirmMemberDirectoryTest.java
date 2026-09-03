package com.juriscore.casemanagement.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The rule that keeps another firm's people, and this firm's outsiders, off its work.
 *
 * <p>Deliberately a wider net than casework's {@code LawyerDirectory}: that one asks who
 * may be counsel on a matter, which only a LAWYER may be; this one asks who may be given
 * a task, which any member of staff may be.
 */
@ExtendWith(MockitoExtension.class)
class IdentityFirmMemberDirectoryTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private UserService userService;

    @InjectMocks
    private IdentityFirmMemberDirectory directory;

    private static User user(Role role, UserStatus status) {
        return User.builder().organizationId(FIRM).email("ravi@firm.test")
                .firstName("Ravi").lastName("Iyer").passwordHash("x")
                .role(role).status(status).build();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"FIRM_ADMIN", "LAWYER", "CLERK"})
    @DisplayName("any active member of the firm's staff can be given work")
    void acceptsEveryActiveStaffRole(Role role) {
        when(userService.getScoped(USER, FIRM)).thenReturn(user(role, UserStatus.ACTIVE));

        assertThatCode(() -> directory.requireAssignableMember(USER, FIRM)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("somebody at another firm is not found, which is also all a caller learns")
    void rejectsAMemberOfAnotherFirm() {
        when(userService.getScoped(USER, FIRM))
                .thenThrow(ApiException.notFound(ErrorCode.USER_NOT_FOUND, USER));

        assertThatThrownBy(() -> directory.requireAssignableMember(USER, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLIENT", "SUPER_ADMIN"})
    @DisplayName("a client and a platform administrator are not the firm's staff")
    void rejectsNonStaffRoles(Role role) {
        when(userService.getScoped(USER, FIRM)).thenReturn(user(role, UserStatus.ACTIVE));

        assertThatThrownBy(() -> directory.requireAssignableMember(USER, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"INVITED", "SUSPENDED", "DEACTIVATED"})
    @DisplayName("somebody suspended, invited or gone cannot be given work")
    void rejectsEveryNonActiveStatus(UserStatus status) {
        when(userService.getScoped(USER, FIRM)).thenReturn(user(Role.LAWYER, status));

        assertThatThrownBy(() -> directory.requireAssignableMember(USER, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }
}
