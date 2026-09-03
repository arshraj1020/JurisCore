package com.juriscore.casework.api.dto;

import com.juriscore.casework.domain.CaseStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeCaseStatusRequest(@NotNull CaseStatus status) {
}
