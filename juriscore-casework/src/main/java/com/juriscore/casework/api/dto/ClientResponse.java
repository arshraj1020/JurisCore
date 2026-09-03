package com.juriscore.casework.api.dto;

import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.ClientType;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code deletedAt} is exposed rather than hidden: a soft-deleted client is still
 * reachable by id so that an old matter resolves to a name, and a caller that fetches
 * one needs to be able to tell.
 */
public record ClientResponse(
        UUID id,
        String displayName,
        ClientType clientType,
        String email,
        String phone,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String country,
        String postalCode,
        String notes,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ClientResponse from(Client client) {
        return new ClientResponse(
                client.getId(),
                client.getDisplayName(),
                client.getClientType(),
                client.getEmail(),
                client.getPhone(),
                client.getAddressLine1(),
                client.getAddressLine2(),
                client.getCity(),
                client.getState(),
                client.getCountry(),
                client.getPostalCode(),
                client.getNotes(),
                client.getDeletedAt(),
                client.getCreatedAt(),
                client.getUpdatedAt());
    }
}
