package com.juriscore.notifications.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.DomainEvent;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.notifications.domain.Notification;
import com.juriscore.notifications.domain.NotificationCategory;
import com.juriscore.notifications.domain.NotificationPreference;
import com.juriscore.notifications.domain.NotificationType;
import com.juriscore.notifications.event.NotificationCreatedEvent;
import com.juriscore.notifications.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The three rules that stop a notification being raised, and the one that lets it through. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID INVOICE = UUID.randomUUID();

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationPreferenceService preferences;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private NotificationService notificationService;

    private static NotificationRequest request(NotificationType type, String dedupeKey) {
        return NotificationRequest.to(FIRM, USER, type)
                .saying("Invoice issued", "Invoice INV-2026-000001 has been issued.")
                .about("INVOICE", INVOICE)
                .linkingTo("/invoices/" + INVOICE)
                .onceFor(dedupeKey)
                .build();
    }

    private void everythingEnabled() {
        when(preferences.effectiveFor(USER, FIRM)).thenReturn(new NotificationPreference());
    }

    private void savesWhatItIsGiven() {
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(call -> {
            Notification n = call.getArgument(0);
            if (n.getId() == null) {
                n.setId(UUID.randomUUID());
            }
            return n;
        });
    }

    @Test
    void raisesANotificationAndDerivesItsCategoryAndSeverity() {
        everythingEnabled();
        savesWhatItIsGiven();

        Notification notification = notificationService
                .raise(request(NotificationType.INVOICE_OVERDUE, "invoice.overdue:" + INVOICE))
                .orElseThrow();

        assertThat(notification.getRecipientUserId()).isEqualTo(USER);
        assertThat(notification.getOrganizationId()).isEqualTo(FIRM);
        assertThat(notification.getCategory()).isEqualTo(NotificationCategory.INVOICE);
        assertThat(notification.getSeverity())
                .isEqualTo(NotificationType.INVOICE_OVERDUE.defaultSeverity());
        assertThat(notification.getActionPath()).startsWith("/invoices/");
        assertThat(notification.isRead()).isFalse();
    }

    @Test
    @DisplayName("a category the recipient has turned off produces nothing at all")
    void preferencesSuppress() {
        NotificationPreference muted = new NotificationPreference();
        muted.set(NotificationCategory.INVOICE, false);
        when(preferences.effectiveFor(USER, FIRM)).thenReturn(muted);

        assertThat(notificationService.raise(request(NotificationType.INVOICE_ISSUED, null)))
                .isEmpty();

        verify(notificationRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("muting one category leaves the others alone")
    void preferencesAreCategoryScoped() {
        NotificationPreference muted = new NotificationPreference();
        muted.set(NotificationCategory.INVOICE, false);
        when(preferences.effectiveFor(USER, FIRM)).thenReturn(muted);
        savesWhatItIsGiven();

        assertThat(notificationService.raise(request(NotificationType.PAYMENT_RECEIVED, null)))
                .isPresent();
    }

    @Test
    @DisplayName("the same business fact reaches a person once")
    void deduplicatesOnTheKey() {
        everythingEnabled();
        when(notificationRepository.existsByRecipientUserIdAndDedupeKey(
                eq(USER), anyString())).thenReturn(true);

        assertThat(notificationService.raise(
                request(NotificationType.INVOICE_OVERDUE, "invoice.overdue:" + INVOICE)))
                .isEmpty();

        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a null dedupe key means genuinely repeatable, and is not checked")
    void aNullKeySkipsTheCheck() {
        everythingEnabled();
        savesWhatItIsGiven();

        assertThat(notificationService.raise(request(NotificationType.SYSTEM_MESSAGE, null)))
                .isPresent();
        verify(notificationRepository, never())
                .existsByRecipientUserIdAndDedupeKey(any(), any());
    }

    @Test
    @DisplayName("the unique index catches what the check cannot: two instances at once")
    void theIndexIsTheRealGuard() {
        everythingEnabled();
        when(notificationRepository.existsByRecipientUserIdAndDedupeKey(eq(USER), anyString()))
                .thenReturn(false);
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notifications_dedupe"));

        // Suppressed, not failed: a duplicate notification must not break the listener
        // reacting to a perfectly good business event.
        assertThat(notificationService.raise(
                request(NotificationType.INVOICE_ISSUED, "invoice.issued:" + INVOICE)))
                .isEmpty();
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void aRequestWithNoRecipientDoesNothing() {
        assertThat(notificationService.raise(
                NotificationRequest.to(FIRM, null, NotificationType.INVOICE_ISSUED).build()))
                .isEmpty();
        assertThat(notificationService.raise(
                NotificationRequest.to(null, USER, NotificationType.INVOICE_ISSUED).build()))
                .isEmpty();
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("notification.created carries the recipient and the type, not the message")
    void publishesWithoutTheContent() {
        everythingEnabled();
        savesWhatItIsGiven();

        notificationService.raise(request(NotificationType.INVOICE_ISSUED, null));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        NotificationCreatedEvent event = (NotificationCreatedEvent) published.getValue();
        assertThat(event.eventType()).isEqualTo("notification.created");
        assertThat(event.getRecipientUserId()).isEqualTo(USER);
        assertThat(event.getNotificationType()).isEqualTo(NotificationType.INVOICE_ISSUED);
        assertThat(event.toString())
                .as("a notification's text can name a client and an amount; the bus does not "
                        + "need to carry it")
                .doesNotContain("INV-2026-000001");
    }

    @Test
    @DisplayName("a colleague's notification is not found, exactly as another firm's is not")
    void anotherUsersNotificationIsNotFound() {
        UUID other = UUID.randomUUID();
        when(notificationRepository.findByIdAndOrganizationIdAndRecipientUserId(
                other, FIRM, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.require(other, USER, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
