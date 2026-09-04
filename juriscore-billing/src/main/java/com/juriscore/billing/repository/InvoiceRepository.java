package com.juriscore.billing.repository;

import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method carries {@code organizationId} — there is deliberately no lookup that does
 * not, the same rule casework, case-management and documents follow.
 *
 * <p>List ordering is baked into the method name rather than left to a caller's
 * {@code Pageable}: newest first with the id as a tiebreak, matching
 * {@code idx_invoices_organization_created}. Several invoices raised in the same second
 * would otherwise page unstably.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /**
     * The lookup every single-invoice request path uses, with the lines fetched alongside.
     *
     * <p>The entity graph is not an optimisation, it is a correctness fix.
     * {@code spring.jpa.open-in-view} is false — deliberately, because a request thread
     * holding a database connection while it serialises JSON is how a pool runs dry — so
     * the persistence context is closed by the time the controller maps the response. A
     * lazily-mapped {@code lineItems} throws {@code LazyInitializationException} there, and
     * it throws it from inside the mapper, which surfaces as a 500 on an operation that
     * actually succeeded and committed. Fetching the collection inside the transaction is
     * what makes the returned entity safe to read afterwards.
     *
     * <p>A join fetch on a to-many is safe here because this returns one row by primary
     * key. It is deliberately not applied to the paged queries below, where fetch-joining a
     * collection would force Hibernate to paginate in memory.
     */
    @EntityGraph(attributePaths = "lineItems")
    Optional<Invoice> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * The same lookup, under a row lock, for the one operation that has to serialise:
     * recording a payment.
     *
     * <p>Optimistic locking is the wrong tool there and fails in a specific way. Two
     * payments arriving together both read "0 paid of 1000", both decide 600 is
     * acceptable, and the version column turns the second into a 409 — which is a lie
     * told to a bookkeeper whose payment was perfectly valid, and which would have
     * silently allowed 1200 against a 1000 invoice had the first transaction not also
     * touched the invoice row. {@code PESSIMISTIC_WRITE} makes the second caller wait for
     * the first to commit and then read the truth, so overpayment is refused because it
     * is overpayment rather than because two people were quick.
     *
     * <p>The lock is on the invoice, not the payments, because the invoice is the thing
     * whose balance is being decided. It is held to commit, so the payment insert and the
     * status transition are inside it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id and i.organizationId = :organizationId")
    Optional<Invoice> findByIdAndOrganizationIdForUpdate(@Param("id") UUID id,
                                                         @Param("organizationId") UUID organizationId);

    Page<Invoice> findByOrganizationIdOrderByCreatedAtDescIdDesc(
            UUID organizationId, Pageable pageable);

    Page<Invoice> findByOrganizationIdAndStatusOrderByCreatedAtDescIdDesc(
            UUID organizationId, InvoiceStatus status, Pageable pageable);

    Page<Invoice> findByOrganizationIdAndClientIdOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID clientId, Pageable pageable);

    Page<Invoice> findByOrganizationIdAndCaseIdOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID caseId, Pageable pageable);

    Page<Invoice> findByOrganizationIdAndClientIdAndStatusOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID clientId, InvoiceStatus status, Pageable pageable);

    Page<Invoice> findByOrganizationIdAndCaseIdAndStatusOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID caseId, InvoiceStatus status, Pageable pageable);

    /**
     * Loads the batch the overdue sweep has already claimed.
     *
     * <p><strong>The one method here without an {@code organizationId}, deliberately.</strong>
     * The sweep is the platform's job rather than a tenant's: it runs on a schedule with no
     * signed-in caller, and every firm's invoices go past their due date on the same
     * calendar. Adding a tenant predicate would mean either running the sweep once per firm
     * or passing a tenant the sweep does not have.
     *
     * <p>It is safe because of where the ids come from — {@code OverdueInvoiceClaimer} has
     * just locked exactly these rows in this transaction — and because it is unreachable
     * from any request path: no controller and no request-scoped service calls it.
     */
    List<Invoice> findByIdInOrderByDueDateAsc(List<UUID> ids);
}
