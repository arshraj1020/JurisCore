package com.juriscore.casework.service;

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
 * The rule that keeps another firm's staff, and this firm's non-lawyers, off a matter.
 */
@ExtendWith(MockitoExtension.class)
class IdentityLawyerDirectoryTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private UserService userService;

    @InjectMocks
    private IdentityLawyerDirectory directory;

    private static User user(Role role, UserStatus status) {
        return User.builder().organizationId(FIRM).email("ravi@firm.test")
                .firstName("Ravi").lastName("Iyer").passwordHash("x")
                .role(role).status(status).build();
    }

    @Test
    void acceptsAnActiveLawyerOfTheSameFirm() {
        when(userService.getScoped(USER, FIRM)).thenReturn(user(Role.LAWYER, UserStatus.ACTIVE));

        assertThatCode(() -> directory.requireAssignableLawyer(USER, FIRM)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a lawyer at another firm is not found, which is also all a caller learns")
    void rejectsALawyerFromAnotherFirm() {
        when(userService.getScoped(USER, FIRM))
                .thenThrow(ApiException.notFound(ErrorCode.USER_NOT_FOUND, USER));

        assertThatThrownBy(() -> directory.requireAssignableLawyer(USER, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"FIRM_ADMIN", "CLERK", "CLIENT", "SUPER_ADMIN"})
    @DisplayName("nobody but a LAWYER can be put on a case, however senior")
    void rejectsEveryOtherRole(Role role) {
        when(userService.getScoped(USER, FIRM)).thenReturn(user(role, UserStatus.ACTIVE));

        assertThatThrownBy(() -> directory.requireAssignableLawyer(USER, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"INVITED", "SUSPENDED", "DEACTIVATED"})
    @DisplayName("a lawyer who is suspended, invited or gone cannot be given a matter")
    void rejectsEveryNonActiveStatus(UserStatus status) {
        when(userService.getScoped(USER, FIRM)).thenReturn(user(Role.LAWYER, status));

        assertThatThrownBy(() -> directory.requireAssignableLawyer(USER, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }
}
