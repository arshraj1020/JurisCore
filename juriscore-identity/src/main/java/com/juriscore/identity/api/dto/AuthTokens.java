package com.juriscore.identity.api.dto;

/**
 * What a successful sign-in or refresh returns.
 *
 * <p>The refresh token is returned in the body rather than set as a cookie because the
 * platform serves a browser SPA and, later, mobile clients. A browser client should
 * keep the access token in memory only.
 *
 * @param expiresIn access token lifetime in seconds
 */
public record AuthTokens(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user) {

    public static AuthTokens of(String accessToken, String refreshToken, long expiresIn, UserResponse user) {
        return new AuthTokens(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
