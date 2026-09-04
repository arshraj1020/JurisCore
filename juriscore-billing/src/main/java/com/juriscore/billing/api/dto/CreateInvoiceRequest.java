package com.juriscore.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A new invoice.
 *
 * <p>No {@code organizationId}: the tenant comes from the access token through
 * {@code CurrentUser}, never from the body, so a caller cannot raise an invoice inside
 * another firm by asking nicely. No {@code invoiceNumber} either — that is issued by
 * {@code InvoiceNumberGenerator} — and no {@code subtotal}, {@code taxAmount} or
 * {@code totalAmount}, which are derived from the lines.
 *
 * <p>Created as a DRAFT. Dates are optional here and required at issue: an invoice being
 * drafted may not yet know when it will go out.
 */
@Schema(description = "A new draft invoice. Numbering, totals and the tenant are server-side.")
public record CreateInvoiceRequest(

        @NotNull
        @Schema(description = "A client of your firm that has not been removed.")
        UUID clientId,

        @Schema(description = "Optional. If given, the matter must belong to the same client.")
        UUID caseId,

        @Schema(description = "ISO 4217. Defaults to your firm's configured currency.",
                example = "INR")
        @Size(min = 3, max = 3)
        String currency,

        @Schema(description = "Optional on a draft; required to issue.")
        LocalDate issueDate,

        @Schema(description = "Optional on a draft; required to issue, and never before the issue date.")
        LocalDate dueDate,

        @DecimalMin(value = "0.00", message = "cannot be negative")
        @Digits(integer = 13, fraction = 2)
        @Schema(description = "Deducted after tax. Cannot exceed the invoice's gross.")
        BigDecimal discountAmount,

        @Size(max = 2000)
        String notes,

        @NotEmpty(message = "an invoice needs at least one line")
        @Valid
        List<InvoiceLineItemRequest> lineItems) {
}
