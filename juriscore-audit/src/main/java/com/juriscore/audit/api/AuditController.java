package com.juriscore.audit.api;

import com.juriscore.audit.api.dto.AuditEventResponse;
import com.juriscore.audit.service.AuditQueryService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The firm's audit trail.
 *
 * <p><strong>GET and nothing else.</strong> There is no PUT, no PATCH and no DELETE on this
 * controller, so append-only is not a rule that has to be authorized — it is a set of
 * endpoints that do not exist. That is the outermost of the four layers described on
 * {@code AuditEvent}; the entity, the mapping and the repository are the other three.
 *
 * <p>{@code FIRM_ADMIN} only. An audit trail records who did what, which makes it a record
 * about a firm's own staff: a clerk should not be able to page through what a partner has
 * been doing, and a lawyer should not be able to check whether anyone noticed a change they
 * made. The platform already reserves its administrative reads for this role, and this is
 * the most administrative read there is.
 *
 * <p>{@code SUPER_ADMIN} gets nothing here either. It has no organization of its own, so
 * {@code requireOrganizationId()} refuses it before any handler body runs — a platform role
 * must not read one firm's audit trail by virtue of being a platform role. Operating the
 * platform is a different problem from investigating a tenant, and the second needs an
 * answer better than "the support engineer had a token".
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Audit", description = "An append-only record of what happened in your firm")
public class AuditController {

    private final AuditQueryService auditQueryService;

    @GetMapping("/api/v1/audit")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Search your firm's audit trail",
            description = "Most recent first, with the id as a tiebreak so paging is stable. "
                    + "Every filter is optional; the tenant is not a filter, it comes from "
                    + "your token. from and to are inclusive.")
    public ApiResponse<PageResponse<AuditEventResponse>> search(
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50) Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                auditQueryService.search(organizationId, actor, action, entityType, entityId,
                        from, to, pageable),
                AuditEventResponse::from));
    }

    @GetMapping("/api/v1/audit/{auditEventId}")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "One audit record")
    public ApiResponse<AuditEventResponse> byId(@PathVariable UUID auditEventId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(AuditEventResponse.from(
                auditQueryService.require(auditEventId, organizationId)));
    }
}
