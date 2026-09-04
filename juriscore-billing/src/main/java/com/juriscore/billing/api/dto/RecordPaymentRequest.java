package com.juriscore.billing.api.dto;

import com.juriscore.billing.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A record that money arrived.
 *
 * <p>Recording only. Nothing about this request charges anybody: JurisCore is connected to
 * no gateway, card network, UPI handle or bank, and {@code method} is a label rather than
 * an instruction. There is no field here for a card number, an account number or a token,
 * and no column behind it that could hold one.
 */
@Schema(description = "Record a payment already received. JurisCore takes no payments itself.")
public record RecordPaymentRequest(

        @NotNull
        @DecimalMin(value = "0.01", message = "must be greater than zero")
        @Digits(integer = 13, fraction = 2)
        BigDecimal amount,

        @Size(min = 3, max = 3)
        @Schema(description = "Must match the invoice's currency. Nothing is ever converted.",
                example = "INR")
        String currency,

        @Schema(description = "Defaults to today.")
        LocalDate paymentDate,

        @NotNull
        PaymentMethod method,

        @Size(max = 120)
        @Schema(description = "A cheque number, a UPI reference, a bank narration.",
                example = "UTR 220414512345")
        String reference,

        @Size(max = 1000)
        String notes) {
}
