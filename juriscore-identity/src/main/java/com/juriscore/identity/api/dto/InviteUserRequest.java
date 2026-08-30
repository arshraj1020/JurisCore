package com.juriscore.identity.api.dto;

import com.juriscore.common.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Adds a lawyer, clerk, second admin or client portal user to the caller's firm.
 * No password: the invitee sets one through the reset-password flow.
 */
public record InviteUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Size(max = 40) String phone,
        @NotNull Role role) {
}
