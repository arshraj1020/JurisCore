package com.juriscore.billing.domain;

import com.juriscore.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One line on an invoice.
 *
 * <p>Not tenant-aware, and that is not an omission: a line item has no independent
 * existence. It is reachable only through its invoice, which is tenant-scoped, and it
 * carries a real foreign key to that invoice because both live in the {@code billing}
 * schema. Giving it its own {@code organization_id} would create a second copy of a fact
 * that could then disagree with the first.
 *
 * <p>{@link #amount} and {@link #taxAmount} are computed by {@code InvoiceCalculator} and
 * never accepted from a request. A client that sends its own totals is sending a
 * suggestion the server ignores.
 */
@Entity
@Table(name = "invoice_line_items", schema = "billing")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceLineItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /** Scale 3. Hours, pages, appearances — whatever the firm is charging for. */
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    /** {@code round(quantity × unitPrice)}. Server-computed. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** A percentage: {@code 18.000} means 18%. */
    @Column(name = "tax_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal taxRate;

    /** {@code round(amount × taxRate ÷ 100)}. Server-computed. */
    @Column(name = "tax_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal taxAmount;

    /** Position on the printed invoice. Unique within the invoice. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
