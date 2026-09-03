package com.juriscore.casework.api;

import com.juriscore.casework.api.dto.ClientResponse;
import com.juriscore.casework.api.dto.CreateClientRequest;
import com.juriscore.casework.api.dto.UpdateClientRequest;
import com.juriscore.casework.service.ClientService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The firm's client book.
 *
 * <p>Roles are declared here, next to the handler, rather than in {@code SecurityConfig}
 * — the convention the identity module already follows, so that whoever reads a
 * controller can see who may call it. {@code SUPER_ADMIN} and {@code CLIENT} appear in
 * none of these lists and are refused: the former has no organization to scope to, the
 * latter has no sharing mechanism to be scoped by until a later phase.
 *
 * <p>No endpoint takes an organization id. It comes from the access token, every time.
 */
@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
@Tag(name = "Clients", description = "Parties the firm acts for")
public class ClientController {

    private final ClientService clientService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List your firm's clients",
            description = "Soft-deleted clients are excluded.")
    public ApiResponse<PageResponse<ClientResponse>> list(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "displayName") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                clientService.list(organizationId, search, pageable), ClientResponse::from));
    }

    @GetMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one client",
            description = "Returns soft-deleted clients too, so that an older matter still "
                    + "resolves to a name. Check deletedAt.")
    public ApiResponse<ClientResponse> byId(@PathVariable UUID clientId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(ClientResponse.from(clientService.getScoped(clientId, organizationId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'CLERK')")
    @Operation(summary = "Add a client")
    public ApiResponse<ClientResponse> create(@Valid @RequestBody CreateClientRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(ClientResponse.from(clientService.create(organizationId, request)),
                "Client created successfully");
    }

    @PutMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'CLERK')")
    @Operation(summary = "Update a client")
    public ApiResponse<ClientResponse> update(@PathVariable UUID clientId,
                                              @Valid @RequestBody UpdateClientRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                ClientResponse.from(clientService.update(clientId, organizationId, request)),
                "Client updated successfully");
    }

    @DeleteMapping("/{clientId}")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Remove a client from the live book",
            description = "Soft deletion. The row stays so that existing cases and timeline "
                    + "entries keep resolving; it disappears from lists and can no longer be "
                    + "chosen for a new case.")
    public ApiResponse<ClientResponse> delete(@PathVariable UUID clientId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                ClientResponse.from(clientService.softDelete(clientId, organizationId)),
                "Client deleted successfully");
    }
}
