package com.juriscore.notifications.repository;

import com.juriscore.notifications.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Keyed by the user, because the preference is the user's and not the firm's. */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    Optional<NotificationPreference> findByUserId(UUID userId);
}
