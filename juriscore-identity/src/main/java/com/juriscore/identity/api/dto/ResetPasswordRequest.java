package com.juriscore.identity.api.dto;

import com.juriscore.identity.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank String token,
        @StrongPassword String newPassword) {
}
