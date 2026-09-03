package com.juriscore.casework.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The case number is absent on purpose: it is issued by the system, per firm and per
 * year, and a caller-supplied one would be a second source of truth.
 */
public record CreateCaseRequest(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 4000) String description,
        @NotNull UUID clientId) {
}
