package com.juriscore.notifications.service;

import com.juriscore.notifications.api.dto.UpdateNotificationPreferencesRequest;
import com.juriscore.notifications.domain.NotificationCategory;
import com.juriscore.notifications.domain.NotificationPreference;
import com.juriscore.notifications.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A user's notification switches.
 *
 * <p>The user's, not the firm's. Every method here takes the id from the caller's own
 * token — there is no endpoint that accepts somebody else's — so an administrator can
 * neither read nor mute a colleague's settings. That is deliberate: an administrator
 * silencing a partner's overdue-invoice alerts is not an administrative capability anybody
 * asked for.
 *
 * <p>Absence means everything is on. A user who has never touched this has no row, and
 * {@link #effectiveFor} answers with a transient default rather than creating one — so a
 * firm's users cost nothing until one of them actually changes something, and the
 * notification path never writes on a read.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;

    /**
     * What this user's settings actually are, saved or not.
     *
     * <p>{@code REQUIRES_NEW}-safe by being read-only and side-effect free: it is called
     * from inside {@code NotificationService.raise}, which runs in its own transaction from
     * an after-commit listener.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public NotificationPreference effectiveFor(UUID userId, UUID organizationId) {
        return preferenceRepository.findByUserId(userId)
                .orElseGet(() -> defaults(userId, organizationId));
    }

    /**
     * Applies a partial edit: a null switch means "leave it alone".
     *
     * <p>PATCH semantics rather than PUT, so a client that only wants to mute invoices does
     * not have to send the other three and risk resetting a setting it did not know about.
     */
    @Transactional
    public NotificationPreference update(UUID userId, UUID organizationId,
                                         UpdateNotificationPreferencesRequest request) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> defaults(userId, organizationId));

        apply(preference, NotificationCategory.INVOICE, request.invoice());
        apply(preference, NotificationCategory.PAYMENT, request.payment());
        apply(preference, NotificationCategory.CASE, request.caseUpdates());
        apply(preference, NotificationCategory.SYSTEM, request.system());

        return preferenceRepository.save(preference);
    }

    private static void apply(NotificationPreference preference, NotificationCategory category,
                              Boolean enabled) {
        if (enabled != null) {
            preference.set(category, enabled);
        }
    }

    private NotificationPreference defaults(UUID userId, UUID organizationId) {
        NotificationPreference preference = new NotificationPreference();
        preference.setUserId(userId);
        preference.setOrganizationId(organizationId);
        return preference;
    }
}
