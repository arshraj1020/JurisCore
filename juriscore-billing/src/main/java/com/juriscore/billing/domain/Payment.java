package com.juriscore.billing.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A record that money arrived against an invoice.
 *
 * <p>Every column is mapped {@code updatable = false} and no service exposes an edit. A
 * payment is a statement about something that happened on a date; correcting one means
 * recording the correction, not rewriting the claim — which is the same reason the audit
 * trail has no update path. Phase 5 deliberately ships no payment-reversal endpoint: a
 * refund is a financial document of its own and inventing half of one is worse than
 * having none.
 *
 * <p>Tenant-aware in its own right, even though its invoice already is. The redundancy is
 * the point: it lets every payment query carry an {@code organizationId} predicate
 * without joining, which is the rule every repository in this codebase follows.
 */
@Entity
@Table(name = "payments", schema = "billing")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends TenantAwareEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2, updatable = false)
    private BigDecimal amount;

    /** Always equal to the invoice's currency; the service refuses anything else. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32, updatable = false)
    private PaymentMethod method;

    /** A cheque number, a UPI reference, a bank narration. Never a credential. */
    @Column(name = "reference", length = 120, updatable = false)
    private String reference;

    @Column(name = "notes", length = 1000, updatable = false)
    private String notes;
}
