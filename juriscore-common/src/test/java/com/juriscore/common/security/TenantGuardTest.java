package com.juriscore.common.security;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TenantGuard} is the last of the three tenant-isolation layers. Nothing in
 * Phase 1 is tenant-scoped through it yet — {@code User} deliberately is not a
 * {@code TenantAwareEntity}, because SUPER_ADMIN has no tenant — so these tests exist to
 * prove the guard behaves as documented <em>before</em> Phase 2 hangs cases, hearings and
 * documents off it.
 */
class TenantGuardTest {

    private static final UUID FIRM_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FIRM_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a caller may reach a resource in their own firm")
    void allowsOwnTenant() {
        authenticateAs(FIRM_A, Role.LAWYER);

        assertThatCode(() -> TenantGuard.check(FIRM_A, ErrorCode.CASE_NOT_FOUND))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a foreign firm's resource reports NOT FOUND, never FORBIDDEN")
    void foreignTenantLooksAbsent() {
        authenticateAs(FIRM_A, Role.LAWYER);

        // The distinction matters: 403 would confirm to firm A that this case exists,
        // which is itself a disclosure. 404 says nothing.
        assertThatThrownBy(() -> TenantGuard.check(FIRM_B, ErrorCode.CASE_NOT_FOUND))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CASE_NOT_FOUND);
                    assertThat(e.errorCode()).isNotEqualTo(ErrorCode.ACCESS_DENIED);
                    assertThat(e.errorCode().status().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("a resource with no tenant at all is treated as foreign")
    void nullTenantIsRejected() {
        authenticateAs(FIRM_A, Role.LAWYER);

        assertThatThrownBy(() -> TenantGuard.check((UUID) null, ErrorCode.DOCUMENT_NOT_FOUND))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("a platform administrator is above the tenant boundary")
    void superAdminCrossesTenants() {
        authenticateAs(null, Role.SUPER_ADMIN);

        assertThatCode(() -> TenantGuard.check(FIRM_B, ErrorCode.CASE_NOT_FOUND))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an anonymous request is rejected before the tenant is even considered")
    void anonymousIsUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> TenantGuard.check(FIRM_A, ErrorCode.CASE_NOT_FOUND))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("CurrentUser refuses to hand a tenantless caller a tenant id")
    void superAdminHasNoImplicitTenant() {
        authenticateAs(null, Role.SUPER_ADMIN);

        assertThatThrownBy(CurrentUser::requireOrganizationId)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    private void authenticateAs(UUID organizationId, Role role) {
        AuthenticatedUser principal =
                new AuthenticatedUser(UUID.randomUUID(), organizationId, "user@firm.test", role);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role.authority())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
