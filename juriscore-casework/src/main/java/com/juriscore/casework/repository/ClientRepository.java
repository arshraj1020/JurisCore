package com.juriscore.casework.repository;

import com.juriscore.casework.domain.Client;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Every method carries {@code organizationId}. There is deliberately no lookup that
 * does not — a bare {@code findById} is the one line that would turn a mistyped id
 * into another firm's client list.
 */
public interface ClientRepository extends JpaRepository<Client, UUID> {

    /**
     * Includes soft-deleted rows on purpose: a case opened before the client was
     * removed still has to resolve to a name.
     */
    Optional<Client> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The "still selectable" lookup — used for edits, deletion and case creation. */
    Optional<Client> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    Page<Client> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId, Pageable pageable);

    @Query("""
            select c from Client c
            where c.organizationId = :organizationId
              and c.deletedAt is null
              and (lower(c.displayName) like lower(concat('%', :search, '%'))
                   or lower(c.email) like lower(concat('%', :search, '%')))
            """)
    Page<Client> search(@Param("organizationId") UUID organizationId,
                        @Param("search") String search,
                        Pageable pageable);

    /**
     * Friendly pre-check for a duplicate address on create. The partial unique index
     * stays the real arbiter under concurrency; this only lets the common case answer
     * 409 with a useful message instead of surfacing a constraint name.
     */
    boolean existsByOrganizationIdAndEmailIgnoreCaseAndDeletedAtIsNull(UUID organizationId, String email);

    /** The same check for an edit, ignoring the row being edited. */
    @Query("""
            select case when count(c) > 0 then true else false end from Client c
            where c.organizationId = :organizationId
              and c.deletedAt is null
              and lower(c.email) = lower(:email)
              and c.id <> :excludeId
            """)
    boolean emailTakenByAnother(@Param("organizationId") UUID organizationId,
                                @Param("email") String email,
                                @Param("excludeId") UUID excludeId);
}
