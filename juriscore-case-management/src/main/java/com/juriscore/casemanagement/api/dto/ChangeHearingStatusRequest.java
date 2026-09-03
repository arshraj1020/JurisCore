package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.HearingStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param outcome optional note recorded with the move — what the bench said, why it was
 *                put off. Left alone when absent.
 */
public record ChangeHearingStatusRequest(
        @NotNull HearingStatus status,
        @Size(max = 4000) String outcome) {
}
