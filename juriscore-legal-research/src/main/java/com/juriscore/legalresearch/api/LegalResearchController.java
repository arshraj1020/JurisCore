package com.juriscore.legalresearch.api;

import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.legalresearch.api.dto.LegalJudgmentResponse;
import com.juriscore.legalresearch.api.dto.RegisterJudgmentRequest;
import com.juriscore.legalresearch.service.JudgmentRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Legal Precedent Intelligence — step 2 exposes ingestion only. There is deliberately no
 * research/query endpoint yet: retrieval and evidence-grounded analysis land in a later
 * step, once there is a real, embedded corpus to retrieve from.
 *
 * <p>Same role shape as {@code DocumentController}: any firm staff member who can see a
 * matter's documents can also flag one as a judgment to ingest.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Legal Research", description = "Legal Precedent Intelligence: judgment ingestion")
public class LegalResearchController {

    private final JudgmentRegistrationService registrationService;

    @PostMapping("/api/v1/legal-research/judgments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Register an existing case document as a judgment",
            description = "Ingestion (text extraction, chunking, embedding) runs "
                    + "asynchronously and only once the document's upload has completed; "
                    + "poll the get-by-id endpoint for extractionStatus.")
    public ApiResponse<LegalJudgmentResponse> register(@Valid @RequestBody RegisterJudgmentRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        var judgment = registrationService.register(request.documentId(), organizationId);
        return ApiResponse.ok(LegalJudgmentResponse.from(judgment), "Judgment registered for ingestion");
    }

    @GetMapping("/api/v1/legal-research/judgments/{judgmentId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Get a judgment's ingestion status and extracted metadata")
    public ApiResponse<LegalJudgmentResponse> getById(@PathVariable UUID judgmentId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        var judgment = registrationService.requireById(judgmentId, organizationId);
        return ApiResponse.ok(LegalJudgmentResponse.from(judgment));
    }
}
