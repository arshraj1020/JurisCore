package com.juriscore.billing.api.dto;

import com.juriscore.billing.domain.InvoiceLineItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A billed line, with the amounts the server calculated.")
public record InvoiceLineItemResponse(
        UUID id,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        int sortOrder) {

    public static InvoiceLineItemResponse from(InvoiceLineItem item) {
        return new InvoiceLineItemResponse(
                item.getId(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getAmount(),
                item.getTaxRate(),
                item.getTaxAmount(),
                item.getSortOrder());
    }
}
