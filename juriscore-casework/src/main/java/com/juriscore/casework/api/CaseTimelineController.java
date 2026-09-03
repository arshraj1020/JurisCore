package com.juriscore.casework.api;

import com.juriscore.casework.api.dto.AddTimelineNoteRequest;
import com.juriscore.casework.api.dto.CaseEventResponse;
import com.juriscore.casework.service.CaseTimelineService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What happened to a matter.
 *
 * <p>Two verbs only. There is no PUT and no DELETE here, and that is the whole design:
 * an amended history is not a history, so the append-only guarantee is enforced by the
 * absence of a way to break it rather than by a check somebody can forget.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/timeline")
@RequiredArgsConstructor
@Tag(name = "Case timeline", description = "Append-only history of a matter")
public class CaseTimelineController {

    private final CaseTimelineService timelineService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Read a matter's timeline",
            description = "Newest first, tie-broken by id so paging is stable when entries "
                    + "share a timestamp.")
    public ApiResponse<PageResponse<CaseEventResponse>> list(
            @PathVariable UUID caseId,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                timelineService.list(caseId, organizationId, pageable), CaseEventResponse::from));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Add a note to a matter's timeline",
            description = "Recorded as MANUAL_NOTE. Like every other entry, it cannot "
                    + "afterwards be edited or removed.")
    public ApiResponse<CaseEventResponse> addNote(@PathVariable UUID caseId,
                                                  @Valid @RequestBody AddTimelineNoteRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        UUID actorUserId = CurrentUser.requireUserId();
        return ApiResponse.ok(CaseEventResponse.from(
                        timelineService.addNote(caseId, organizationId, actorUserId, request)),
                "Note added successfully");
    }
}
