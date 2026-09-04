package com.juriscore.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Withdraw an invoice. Terminal: a cancelled invoice can never be paid or reissued.")
public record CancelInvoiceRequest(

        @NotNull
        @Schema(description = "The version you last read.")
        Long version,

        @Size(max = 500)
        @Schema(description = "Appended to the invoice's notes and recorded on the audit trail.")
        String reason) {
}
