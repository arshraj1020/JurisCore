package com.juriscore.legalresearch.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The document to register — already uploaded (or being uploaded) through the existing Documents API. */
public record RegisterJudgmentRequest(@NotNull UUID documentId) {
}
