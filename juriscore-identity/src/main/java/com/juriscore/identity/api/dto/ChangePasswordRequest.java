package com.juriscore.identity.api.dto;

import com.juriscore.identity.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @StrongPassword String newPassword) {
}
