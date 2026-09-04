package com.juriscore.notifications.repository;

import com.juriscore.notifications.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method carries both the organization and the recipient.
 *
 * <p>The recipient predicate is not a convenience — it is the second half of the isolation
 * rule. A notification is addressed to one person, so a colleague in the same firm asking
 * for it must get the same answer as a stranger in another firm: not found. Leaving the
 * recipient out of a query and checking it afterwards would work until somebody added a
 * method and forgot the check, which is precisely the failure this shape prevents.
 *
 * <p>Ordering is in the method name rather than a caller's {@code Pageable}: newest first
 * with the id as a tiebreak, matching {@code idx_notifications_recipient}. Several
 * notifications created by one sweep share a millisecond and would otherwise page unstably.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndOrganizationIdAndRecipientUserId(
            UUID id, UUID organizationId, UUID recipientUserId);

    Page<Notification> findByOrganizationIdAndRecipientUserIdOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID recipientUserId, Pageable pageable);

    Page<Notification> findByOrganizationIdAndRecipientUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID recipientUserId, Pageable pageable);

    long countByOrganizationIdAndRecipientUserIdAndReadAtIsNull(
            UUID organizationId, UUID recipientUserId);

    boolean existsByRecipientUserIdAndDedupeKey(UUID recipientUserId, String dedupeKey);

    /**
     * Marks everything unread as read, in one statement.
     *
     * <p>A targeted update rather than a load-and-loop: a user coming back from leave may
     * have hundreds, and pulling them all into a persistence context to set one timestamp
     * on each is work for nothing. It also cannot overwrite anything else on the rows,
     * which a stale in-memory copy could.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
               set n.readAt = :readAt, n.updatedAt = :readAt
             where n.organizationId = :organizationId
               and n.recipientUserId = :recipientUserId
               and n.readAt is null
            """)
    int markAllRead(@Param("organizationId") UUID organizationId,
                    @Param("recipientUserId") UUID recipientUserId,
                    @Param("readAt") Instant readAt);
}
