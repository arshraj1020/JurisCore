package com.juriscore.identity.security;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque token generation and hashing for refresh / password-reset tokens.
 *
 * <p>SHA-256 rather than BCrypt here is deliberate: these tokens are 256 bits of
 * output from a CSPRNG, so there is nothing to brute-force and the lookup has to be
 * an indexed equality match. Passwords, which are low-entropy, use BCrypt instead.
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;

    private TokenHasher() {
    }

    /** A fresh 256-bit token, URL-safe. This value is shown once and never stored. */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** 64-character lowercase hex digest — matches the {@code char(64)} columns. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "SHA-256 is unavailable", e);
        }
    }
}
