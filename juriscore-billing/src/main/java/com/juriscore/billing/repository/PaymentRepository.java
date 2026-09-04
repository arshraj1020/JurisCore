package com.juriscore.billing.repository;

import com.juriscore.billing.domain.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

/** Tenant-scoped like everything else; a payment is never looked up by id alone. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Page<Payment> findByOrganizationIdAndInvoiceIdOrderByPaymentDateDescIdDesc(
            UUID organizationId, UUID invoiceId, Pageable pageable);

    /**
     * What has been paid so far, summed in the database rather than in Java.
     *
     * <p>Called inside the transaction that holds the invoice's row lock, so the figure it
     * returns cannot be stale by the time it is used — which is the entire reason the lock
     * is taken before this runs rather than after.
     *
     * <p>{@code coalesce} because {@code sum} over no rows is null, and a null total would
     * make the first payment on every invoice an arithmetic error.
     */
    @Query("""
            select coalesce(sum(p.amount), 0)
              from Payment p
             where p.organizationId = :organizationId
               and p.invoiceId = :invoiceId
            """)
    BigDecimal totalPaid(@Param("organizationId") UUID organizationId,
                         @Param("invoiceId") UUID invoiceId);
}
