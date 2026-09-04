package com.juriscore.audit.repository;

import com.juriscore.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads, and exactly one write.
 *
 * <p><strong>Deliberately not {@code JpaRepository}.</strong> Extending it would inherit
 * {@code save}, {@code saveAll}, {@code delete}, {@code deleteById}, {@code deleteAll} and
 * {@code flush} — six ways to mutate an audit trail, sitting on the interface for anyone
 * who reaches for autocomplete. This extends Spring Data's bare {@code Repository} marker
 * and declares what it is allowed to do. The immutability is in the type, not in a comment
 * asking people not to.
 *
 * <p>{@link JpaSpecificationExecutor} supplies the filtered read. It is a read-only
 * interface — {@code findAll}, {@code findOne}, {@code count}, {@code exists} — so it adds
 * query power without adding a way to write. It is also why the search is a specification
 * rather than one HQL query with six {@code :param is null or …} clauses: that form has
 * Hibernate 6 inferring a type for a null parameter, which this codebase has avoided
 * everywhere else, and it makes the database plan for filters the caller did not use.
 * {@code AuditSpecifications} builds only the predicates that were actually asked for.
 */
public interface AuditEventRepository
        extends Repository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    /** The only write. Named for what it does, so nothing here reads as an overwrite. */
    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
