package com.juriscore.common.security;

import com.juriscore.common.domain.TenantAwareEntity;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.UUID;

/**
 * Last line of defence for cross-tenant access.
 *
 * <p>Repository queries already filter by organization; this guard exists for the
 * day someone adds a {@code findById} without thinking. Returning 404 rather than
 * 403 for a foreign resource is intentional — a 403 confirms the record exists.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    public static void check(TenantAwareEntity entity, ErrorCode notFoundCode) {
        check(entity.getOrganizationId(), notFoundCode);
    }

    public static void check(UUID resourceOrganizationId, ErrorCode notFoundCode) {
        AuthenticatedUser caller = CurrentUser.require();
        if (caller.isSuperAdmin()) {
            return;
        }
        if (resourceOrganizationId == null || !caller.belongsTo(resourceOrganizationId)) {
            throw new ApiException(notFoundCode);
        }
    }
}
