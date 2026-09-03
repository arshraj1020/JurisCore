package com.juriscore.casework.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Status is absent, and that is the point: the lifecycle has exactly one door, and it
 * is {@code PATCH /cases/{id}/status}. A status field here would let an ordinary edit
 * slip a matter into CLOSED without passing the transition rules.
 *
 * @param version the value the caller last read from {@code CaseResponse.version}.
 *                Required, because optimistic locking that the client cannot participate
 *                in is not optimistic locking — it is last-write-wins with a column. A
 *                stale value answers 409 {@code CONCURRENT_MODIFICATION}.
 */
public record UpdateCaseRequest(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 4000) String description,
        @NotNull UUID clientId,
        @NotNull Long version) {
}
