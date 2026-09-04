package com.juriscore.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An edit to an invoice.
 *
 * <p>What may actually change depends on the invoice's state, and the service — not this
 * record — is where that is decided:
 *
 * <ul>
 *   <li><strong>DRAFT.</strong> Everything here. It has not been sent to anybody.</li>
 *   <li><strong>Anything else.</strong> {@code notes} only. Lines, client, matter, dates,
 *       discount and currency are frozen the moment the invoice is issued, and sending
 *       any of them against an issued invoice is a 409 rather than a silent no-op —
 *       a caller who thinks they corrected an issued invoice's figures must not be left
 *       believing it.</li>
 * </ul>
 *
 * <p>There is no field for the invoice number and none for the totals, in either state.
 * Correcting an issued invoice's money is a cancellation and a new invoice; Phase 5 has
 * no credit note, and half of one would be worse than none.
 */
@Schema(description = "An edit. Only notes may change once the invoice has been issued.")
public record UpdateInvoiceRequest(

        @NotNull
        @Schema(description = "The version you last read. A stale value is a 409.")
        Long version,

        UUID clientId,

        UUID caseId,

        LocalDate issueDate,

        LocalDate dueDate,

        @DecimalMin(value = "0.00", message = "cannot be negative")
        @Digits(integer = 13, fraction = 2)
        BigDecimal discountAmount,

        @Size(max = 2000)
        String notes,

        @Valid
        List<InvoiceLineItemRequest> lineItems) {
}
