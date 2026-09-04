package com.juriscore.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Sending a draft out.
 *
 * <p>The dates are here rather than read off the draft because issuing is the moment they
 * become real. A draft may carry a proposed issue date from a week ago; the invoice a
 * client receives should say when it was actually sent.
 */
@Schema(description = "Issue a draft. Both dates are required, and the due date cannot precede the issue date.")
public record IssueInvoiceRequest(

        @NotNull
        @Schema(description = "The version you last read.")
        Long version,

        @Schema(description = "Defaults to today if omitted.")
        LocalDate issueDate,

        @Schema(description = "Defaults to the invoice's existing due date if it has one.")
        LocalDate dueDate) {
}
