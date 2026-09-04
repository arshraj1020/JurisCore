package com.juriscore.audit.service;

import com.juriscore.audit.domain.AuditEvent;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the audit search predicate from whichever filters were supplied.
 *
 * <p>Only the filters a caller actually sent become predicates, so the database is never
 * asked to plan around six {@code is null or} clauses that are all null — and Hibernate is
 * never asked to infer a type for a null parameter, which is the problem this codebase has
 * avoided everywhere else.
 *
 * <p>{@code organizationId} is not a filter and is not optional. It is always the first
 * predicate, taken from the caller's token, so there is no combination of query parameters
 * that returns another firm's rows and none that returns every firm's.
 */
final class AuditSpecifications {

    private AuditSpecifications() {
    }

    static Specification<AuditEvent> matching(UUID organizationId, UUID actorUserId, String action,
                                              String entityType, UUID entityId, Instant from,
                                              Instant to) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("organizationId"), organizationId));

            if (actorUserId != null) {
                predicates.add(builder.equal(root.get("actorUserId"), actorUserId));
            }
            if (action != null) {
                predicates.add(builder.equal(root.get("action"), action));
            }
            if (entityType != null) {
                predicates.add(builder.equal(root.get("entityType"), entityType));
            }
            if (entityId != null) {
                predicates.add(builder.equal(root.get("entityId"), entityId));
            }
            // Both bounds inclusive: somebody filling in "from the 1st to the 31st" means
            // the whole of both days.
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return builder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
