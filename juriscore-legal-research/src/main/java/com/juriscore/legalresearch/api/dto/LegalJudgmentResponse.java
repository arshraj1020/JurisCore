package com.juriscore.legalresearch.api.dto;

import com.juriscore.legalresearch.domain.JudgmentExtractionStatus;
import com.juriscore.legalresearch.domain.LegalJudgment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A judgment's ingestion status and whatever metadata extraction has "
        + "confidently identified so far. Fields left null were not extracted, never guessed.")
public record LegalJudgmentResponse(
        UUID id,
        UUID sourceDocumentId,
        String caseName,
        String court,
        LocalDate decidedDate,
        String judges,
        String citation,
        Integer pageCount,
        JudgmentExtractionStatus extractionStatus,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static LegalJudgmentResponse from(LegalJudgment judgment) {
        return new LegalJudgmentResponse(
                judgment.getId(),
                judgment.getSourceDocumentId(),
                judgment.getCaseName(),
                judgment.getCourt(),
                judgment.getDecidedDate(),
                judgment.getJudges(),
                judgment.getCitation(),
                judgment.getPageCount(),
                judgment.getExtractionStatus(),
                judgment.getFailureReason(),
                judgment.getCreatedAt(),
                judgment.getUpdatedAt(),
                judgment.getVersion());
    }
}
