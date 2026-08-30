package com.juriscore.identity.api.dto;

import com.juriscore.identity.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-serve firm signup: provisions the organization and its first FIRM_ADMIN
 * together. Staff and clients are added later by invitation, not through this endpoint.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 200) String firmName,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @StrongPassword String password,
        @Size(max = 40) String phone,
        @Size(max = 64) String timezone) {
}
