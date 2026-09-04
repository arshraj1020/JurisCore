package com.juriscore.billing.api.dto;

import com.juriscore.billing.domain.Payment;
import com.juriscore.billing.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A recorded payment. Nothing here was collected by the platform.")
public record PaymentResponse(
        UUID id,
        UUID invoiceId,
        BigDecimal amount,
        String currency,
        LocalDate paymentDate,
        PaymentMethod method,
        String reference,
        String notes,
        Instant createdAt,
        UUID createdBy) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getInvoiceId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaymentDate(),
                payment.getMethod(),
                payment.getReference(),
                payment.getNotes(),
                payment.getCreatedAt(),
                payment.getCreatedBy());
    }
}
