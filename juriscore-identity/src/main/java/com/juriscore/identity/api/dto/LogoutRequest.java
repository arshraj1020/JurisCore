package com.juriscore.identity.api.dto;

/**
 * @param refreshToken the session to end; when null every session for the caller is revoked
 */
public record LogoutRequest(String refreshToken) {
}
