package com.juriscore.billing.api.dto;

import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An invoice as clients of the API see it.
 *
 * <p>No {@code organizationId}. Every response in JurisCore omits it: the caller's tenant
 * is implicit in their token, and echoing internal tenant identifiers back gives a client
 * something to start substituting into other requests.
 *
 * <p>{@code amountPaid} and {@code amountDue} are carried alongside the stored totals
 * because the alternative is every caller re-deriving them, and a client that computes its
 * own balance is a client that will eventually compute it differently from the server.
 */
@Schema(description = "An invoice, its lines and its balance.")
public record InvoiceResponse(
        UUID id,
        String invoiceNumber,
        UUID clientId,
        UUID caseId,
        InvoiceStatus status,
        LocalDate issueDate,
        LocalDate dueDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        @Schema(description = "Sum of payments recorded against this invoice.")
        BigDecimal amountPaid,
        @Schema(description = "totalAmount − amountPaid. Zero once settled.")
        BigDecimal amountDue,
        String notes,
        Instant paidAt,
        Instant cancelledAt,
        List<InvoiceLineItemResponse> lineItems,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy,
        long version) {

    /**
     * The same invoice without its lines, for list responses.
     *
     * <p>Two reasons, and either would be enough. A page of twenty invoices carrying up to
     * two hundred lines each is a payload nobody asked for; and fetch-joining a collection
     * across a paged query forces Hibernate to paginate in memory, which is a correctness
     * problem long before it is a performance one.
     *
     * <p>{@code lineItems} is null rather than empty, so the field is absent from the JSON
     * entirely under this API's {@code non_null} inclusion. Absent says "not included
     * here"; an empty array would say "this invoice has no lines", which is never true —
     * an invoice cannot be created without at least one.
     */
    public static InvoiceResponse summary(Invoice invoice, BigDecimal amountPaid) {
        return map(invoice, amountPaid, null);
    }

    public static InvoiceResponse from(Invoice invoice, BigDecimal amountPaid) {
        return map(invoice, amountPaid,
                invoice.getLineItems().stream().map(InvoiceLineItemResponse::from).toList());
    }

    /**
     * The shared mapping. {@code lines} is passed in rather than read here, so
     * {@link #summary} can be certain it never touches {@code getLineItems()} — the whole
     * point of the summary is that the collection was not fetched and reading it would
     * throw.
     */
    private static InvoiceResponse map(Invoice invoice, BigDecimal amountPaid,
                                       List<InvoiceLineItemResponse> lines) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getClientId(),
                invoice.getCaseId(),
                invoice.getStatus(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.getCurrency(),
                invoice.getSubtotal(),
                invoice.getTaxAmount(),
                invoice.getDiscountAmount(),
                invoice.getTotalAmount(),
                amountPaid,
                invoice.getTotalAmount().subtract(amountPaid),
                invoice.getNotes(),
                invoice.getPaidAt(),
                invoice.getCancelledAt(),
                lines,
                invoice.getCreatedAt(),
                invoice.getUpdatedAt(),
                invoice.getCreatedBy(),
                invoice.getUpdatedBy(),
                invoice.getVersion());
    }
}
