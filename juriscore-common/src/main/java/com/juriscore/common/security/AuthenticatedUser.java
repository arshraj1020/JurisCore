package com.juriscore.common.security;

import java.util.UUID;

/**
 * The caller, as resolved from a validated access token. Deliberately a value type
 * with no JPA behind it: modules other than identity should never load a User row
 * just to answer "who is calling?".
 *
 * @param organizationId null only for {@link Role#SUPER_ADMIN}, who sits above tenants
 */
public record AuthenticatedUser(UUID userId, UUID organizationId, String email, Role role) {

    public boolean isSuperAdmin() {
        return role != null && role.isPlatformAdmin();
    }

    public boolean belongsTo(UUID tenantId) {
        return organizationId != null && organizationId.equals(tenantId);
    }
}
