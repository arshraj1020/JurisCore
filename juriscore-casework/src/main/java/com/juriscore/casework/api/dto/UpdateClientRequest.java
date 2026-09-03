package com.juriscore.casework.api.dto;

import com.juriscore.casework.domain.ClientType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateClientRequest(
        @NotBlank @Size(max = 200) String displayName,
        @NotNull ClientType clientType,
        @Email @Size(max = 255) String email,
        @Size(max = 40) String phone,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 120) String city,
        @Size(max = 120) String state,
        @Size(max = 120) String country,
        @Size(max = 20) String postalCode,
        @Size(max = 2000) String notes) {
}
