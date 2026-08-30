package com.juriscore.identity.api.dto;

import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

/** Public view of a user. Never carries the password hash or token generation. */
public record UserResponse(
        UUID id,
        UUID organizationId,
        String email,
        String firstName,
        String lastName,
        String fullName,
        String phone,
        Role role,
        UserStatus status,
        Instant lastLoginAt,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getOrganizationId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.fullName(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }
}
