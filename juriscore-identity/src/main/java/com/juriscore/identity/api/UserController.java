package com.juriscore.identity.api;

import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.common.security.Role;
import com.juriscore.identity.api.dto.ChangePasswordRequest;
import com.juriscore.identity.api.dto.InviteUserRequest;
import com.juriscore.identity.api.dto.UpdateProfileRequest;
import com.juriscore.identity.api.dto.UserResponse;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.service.AuthService;
import com.juriscore.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Firm membership, profiles and roles")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    @GetMapping("/me")
    @Operation(summary = "The signed-in user's own profile")
    public ApiResponse<UserResponse> me() {
        return ApiResponse.ok(UserResponse.from(userService.getById(CurrentUser.requireUserId())));
    }

    @PutMapping("/me")
    @Operation(summary = "Update your own name and phone number")
    public ApiResponse<UserResponse> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(
                UserResponse.from(userService.updateProfile(CurrentUser.requireUserId(), request)),
                "Profile updated");
    }

    @PostMapping("/me/change-password")
    @Operation(summary = "Change your own password",
            description = "Signs out every other session, including this one's refresh token.")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.requireUserId(), request.currentPassword(),
                request.newPassword());
        return ApiResponse.message("Password changed. Please sign in again.");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List members of your firm")
    public ApiResponse<PageResponse<UserResponse>> list(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "lastName") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                userService.list(organizationId, role, search, pageable), UserResponse::from));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one member of your firm")
    public ApiResponse<UserResponse> byId(@PathVariable UUID userId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(UserResponse.from(userService.getScoped(userId, organizationId)));
    }

    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Invite a lawyer, clerk, administrator or client to your firm")
    public ApiResponse<UserResponse> invite(@Valid @RequestBody InviteUserRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(UserResponse.from(userService.invite(organizationId, request)),
                "Invitation sent");
    }

    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Suspend, reactivate or deactivate a member")
    public ApiResponse<UserResponse> changeStatus(@PathVariable UUID userId,
                                                  @RequestParam UserStatus status) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                UserResponse.from(userService.changeStatus(organizationId, userId, status)),
                "Status updated");
    }

    @PatchMapping("/{userId}/role")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Change a member's role")
    public ApiResponse<UserResponse> changeRole(@PathVariable UUID userId,
                                                @RequestParam Role role) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                UserResponse.from(userService.changeRole(organizationId, userId, role)),
                "Role updated");
    }
}
