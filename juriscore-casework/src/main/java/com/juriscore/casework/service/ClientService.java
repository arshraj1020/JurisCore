package com.juriscore.casework.service;

import com.juriscore.casework.api.dto.CreateClientRequest;
import com.juriscore.casework.api.dto.UpdateClientRequest;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.event.ClientCreatedEvent;
import com.juriscore.casework.repository.ClientRepository;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** The firm's client book. */
@Service
@RequiredArgsConstructor
public class ClientService {

    private static final Logger log = LoggerFactory.getLogger(ClientService.class);

    private final ClientRepository clientRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public Client create(UUID organizationId, CreateClientRequest request) {
        String email = normalizeEmail(request.email());
        if (email != null
                && clientRepository.existsByOrganizationIdAndEmailIgnoreCaseAndDeletedAtIsNull(
                        organizationId, email)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "A client with this email address already exists in your firm");
        }

        Client client = new Client();
        client.setOrganizationId(organizationId);
        client.setDisplayName(request.displayName().trim());
        client.setClientType(request.clientType());
        client.setEmail(email);
        client.setPhone(request.phone());
        client.setAddressLine1(request.addressLine1());
        client.setAddressLine2(request.addressLine2());
        client.setCity(request.city());
        client.setState(request.state());
        client.setCountry(request.country());
        client.setPostalCode(request.postalCode());
        client.setNotes(request.notes());

        Client saved = clientRepository.save(client);
        log.info("Client {} created for organization {}", saved.getId(), organizationId);
        eventPublisher.publish(
                new ClientCreatedEvent(organizationId, saved.getId(), saved.getDisplayName()));
        return saved;
    }

    /**
     * Any client of this firm, soft-deleted ones included.
     *
     * <p>Deliberate: a matter opened two years ago points at a client who may since have
     * been removed, and a 404 there would make the matter unreadable. The response
     * carries {@code deletedAt} so a caller can tell.
     */
    @Transactional(readOnly = true)
    public Client getScoped(UUID clientId, UUID organizationId) {
        Client client = clientRepository.findByIdAndOrganizationId(clientId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.CLIENT_NOT_FOUND, clientId));
        TenantGuard.check(client, ErrorCode.CLIENT_NOT_FOUND);
        return client;
    }

    /**
     * A client that may still be acted on — edited, deleted, or attached to a new case.
     *
     * <p>A soft-deleted client answers {@code CLIENT_NOT_FOUND}, which is the same answer
     * a client of another firm gets. That is what makes "cannot be selected for new
     * cases" a rule rather than a convention.
     */
    @Transactional(readOnly = true)
    public Client requireSelectable(UUID clientId, UUID organizationId) {
        Client client = clientRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.CLIENT_NOT_FOUND, clientId));
        TenantGuard.check(client, ErrorCode.CLIENT_NOT_FOUND);
        return client;
    }

    @Transactional(readOnly = true)
    public Page<Client> list(UUID organizationId, String search, Pageable pageable) {
        if (search != null && !search.isBlank()) {
            return clientRepository.search(organizationId, search.trim(), pageable);
        }
        return clientRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId, pageable);
    }

    @Transactional
    public Client update(UUID clientId, UUID organizationId, UpdateClientRequest request) {
        Client client = requireSelectable(clientId, organizationId);
        String email = normalizeEmail(request.email());
        if (email != null
                && clientRepository.emailTakenByAnother(organizationId, email, clientId)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "A client with this email address already exists in your firm");
        }

        client.setDisplayName(request.displayName().trim());
        client.setClientType(request.clientType());
        client.setEmail(email);
        client.setPhone(request.phone());
        client.setAddressLine1(request.addressLine1());
        client.setAddressLine2(request.addressLine2());
        client.setCity(request.city());
        client.setState(request.state());
        client.setCountry(request.country());
        client.setPostalCode(request.postalCode());
        client.setNotes(request.notes());
        return client;
    }

    /**
     * Removes a client from the live book without removing the row.
     *
     * <p>A hard delete would either orphan every case that names this client or cascade
     * the matters away with them; both lose records a firm is required to keep. Deleting
     * twice answers {@code CLIENT_NOT_FOUND}, because the second call is asking about
     * something that is no longer in the set it can act on.
     */
    @Transactional
    public Client softDelete(UUID clientId, UUID organizationId) {
        Client client = requireSelectable(clientId, organizationId);
        client.markDeleted(Instant.now());
        log.info("Client {} soft-deleted in organization {}", clientId, organizationId);
        return client;
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
