package com.juriscore.notifications.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.TenantGuard;
import com.juriscore.notifications.domain.Notification;
import com.juriscore.notifications.domain.NotificationPreference;
import com.juriscore.notifications.domain.NotificationType;
import com.juriscore.notifications.event.NotificationCreatedEvent;
import com.juriscore.notifications.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Raising notifications, and reading your own.
 *
 * <h2>Delivery is in-app, and only in-app</h2>
 *
 * <p>Creating a notification writes a row. It sends no email, no SMS, no WhatsApp message
 * and no push, because JurisCore has no such integration and this module adds none. A user
 * sees a notification when they read it through the API.
 *
 * <h2>Three things stop a notification being raised</h2>
 *
 * <ol>
 *   <li>The recipient has turned the category off. Checked here rather than at each call
 *       site, so a producer cannot forget.</li>
 *   <li>The same {@code dedupeKey} already exists for that recipient. Checked, and then
 *       enforced by {@code uk_notifications_dedupe} — the check catches the ordinary case
 *       cheaply and the index catches the race, because two application instances handling
 *       the same event will both pass the check.</li>
 *   <li>There is no recipient. A producer that cannot name a person has nothing to do.</li>
 * </ol>
 *
 * <h2>Why it commits on its own</h2>
 *
 * <p>{@code REQUIRES_NEW}. This is called from {@code AFTER_COMMIT} listeners, where the
 * producing transaction is already finished and there is nothing left to join; without its
 * own transaction the write would either fail or, worse, silently participate in a
 * connection nobody is going to commit. It also means a notification that cannot be
 * written — a duplicate, a constraint — cannot take down the listener that was reacting to
 * a perfectly good business event.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceService preferences;
    private final EventPublisher eventPublisher;

    /**
     * Raises a notification, unless one of the three rules above says not to.
     *
     * @return the notification, or empty when it was suppressed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Notification> raise(NotificationRequest request) {
        if (request.recipientUserId() == null || request.organizationId() == null) {
            return Optional.empty();
        }

        NotificationType type = request.type();
        NotificationPreference preference = preferences.effectiveFor(
                request.recipientUserId(), request.organizationId());
        if (!preference.allows(type.category())) {
            log.debug("Suppressed {} for user {}: {} notifications are off",
                    type, request.recipientUserId(), type.category());
            return Optional.empty();
        }

        if (request.dedupeKey() != null && notificationRepository
                .existsByRecipientUserIdAndDedupeKey(request.recipientUserId(), request.dedupeKey())) {
            log.debug("Suppressed duplicate {} for user {}", type, request.recipientUserId());
            return Optional.empty();
        }

        Notification notification = new Notification();
        notification.setOrganizationId(request.organizationId());
        notification.setRecipientUserId(request.recipientUserId());
        notification.setNotificationType(type);
        notification.setCategory(type.category());
        notification.setSeverity(type.defaultSeverity());
        notification.setTitle(request.title());
        notification.setMessage(request.message());
        notification.setEntityType(request.entityType());
        notification.setEntityId(request.entityId());
        notification.setActionPath(request.actionPath());
        notification.setDedupeKey(request.dedupeKey());

        Notification saved;
        try {
            saved = notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            // The unique index caught what the check above could not: another instance
            // raised the same notification between the two. Suppressed, not failed.
            log.debug("Duplicate {} for user {} refused by the index", type, request.recipientUserId());
            return Optional.empty();
        }

        eventPublisher.publish(new NotificationCreatedEvent(request.organizationId(),
                saved.getId(), saved.getRecipientUserId(), type));
        return Optional.of(saved);
    }

    // ------------------------------------------------------------------------- reading

    /**
     * The caller's own notifications, newest first.
     *
     * <p>{@code recipientUserId} is a parameter rather than something read inside, so the
     * controller's use of {@code CurrentUser.requireUserId()} is visible at the call site
     * — but there is no endpoint that lets a caller supply somebody else's.
     */
    @Transactional(readOnly = true)
    public Page<Notification> list(UUID recipientUserId, UUID organizationId, boolean unreadOnly,
                                   Pageable pageable) {
        return unreadOnly
                ? notificationRepository
                        .findByOrganizationIdAndRecipientUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
                                organizationId, recipientUserId, pageable)
                : notificationRepository
                        .findByOrganizationIdAndRecipientUserIdOrderByCreatedAtDescIdDesc(
                                organizationId, recipientUserId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientUserId, UUID organizationId) {
        return notificationRepository.countByOrganizationIdAndRecipientUserIdAndReadAtIsNull(
                organizationId, recipientUserId);
    }

    /**
     * One of the caller's own notifications.
     *
     * <p>A colleague's answers not-found, exactly as another firm's does. A 403 would
     * confirm it exists, and "who is being told what" is itself worth not disclosing.
     */
    @Transactional(readOnly = true)
    public Notification require(UUID notificationId, UUID recipientUserId, UUID organizationId) {
        Notification notification = notificationRepository
                .findByIdAndOrganizationIdAndRecipientUserId(
                        notificationId, organizationId, recipientUserId)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.RESOURCE_NOT_FOUND, notificationId));
        TenantGuard.check(notification, ErrorCode.RESOURCE_NOT_FOUND);
        return notification;
    }

    // ------------------------------------------------------------------------ mutating

    /** Marking as read twice is a person clicking twice, not an error. */
    @Transactional
    public Notification markRead(UUID notificationId, UUID recipientUserId, UUID organizationId) {
        Notification notification = require(notificationId, recipientUserId, organizationId);
        notification.markRead(Instant.now());
        return notification;
    }

    /** @return how many were still unread */
    @Transactional
    public int markAllRead(UUID recipientUserId, UUID organizationId) {
        return notificationRepository.markAllRead(organizationId, recipientUserId, Instant.now());
    }

    /**
     * Removes one of the caller's own notifications.
     *
     * <p>A hard delete, and the one place in this codebase where that is the right answer:
     * a notification is a message to a person, it carries no history anything else
     * references, and a soft-deleted message they have already dismissed is a row kept for
     * nobody. The audit trail — which does keep history — has no delete at all.
     */
    @Transactional
    public void delete(UUID notificationId, UUID recipientUserId, UUID organizationId) {
        notificationRepository.delete(require(notificationId, recipientUserId, organizationId));
    }
}
