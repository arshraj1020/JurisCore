package com.juriscore.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * One line, as a caller states it.
 *
 * <p>Note what is <em>not</em> here: {@code amount} and {@code taxAmount}. They are
 * computed by {@code InvoiceCalculator} from the three figures below, so there is nothing
 * for a client to over-declare. That is stronger than validating a supplied total, because
 * there is no supplied total to validate.
 */
@Schema(description = "A line to bill. The amount and tax are calculated by the server.")
public record InvoiceLineItemRequest(

        @NotBlank @Size(max = 500)
        @Schema(example = "Drafting written statement")
        String description,

        @NotNull
        @DecimalMin(value = "0.001", message = "must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        @Schema(description = "Hours, pages, appearances — up to three decimals.", example = "2.500")
        BigDecimal quantity,

        @NotNull
        @DecimalMin(value = "0.00", message = "cannot be negative")
        @Digits(integer = 13, fraction = 2)
        @Schema(example = "4000.00")
        BigDecimal unitPrice,

        @DecimalMin(value = "0.000", message = "cannot be negative")
        @DecimalMax(value = "100.000", message = "is a percentage, so cannot exceed 100")
        @Digits(integer = 3, fraction = 3)
        @Schema(description = "A percentage: 18 means 18%. Null is treated as zero.",
                example = "18.000")
        BigDecimal taxRate) {
}
