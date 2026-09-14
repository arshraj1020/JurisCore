package com.juriscore.billing.api.dto;

import com.juriscore.billing.service.InvoiceEmailService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What an accepted invoice email tells the caller.
 *
 * <p>No provider message id, no sending identity, no configuration: those are facts about
 * how this platform is deployed, and a firm's browser has no use for them. What it does
 * need is the address the bill actually went to — so the person who pressed the button can
 * see it went where they expected — and when.
 */
@Schema(description = "Confirmation that the email provider accepted an invoice for delivery.")
public record InvoiceEmailResponse(
        String invoiceNumber,
        @Schema(description = "The client address the invoice was sent to.",
                example = "asha@menon.test")
        String recipient,
        @Schema(description = "When the provider accepted it. Acceptance, not proof of reading.")
        Instant sentAt,
        @Schema(description = "The name the PDF was attached under.",
                example = "invoice-INV-2026-000001.pdf")
        String fileName) {

    public static InvoiceEmailResponse from(InvoiceEmailService.Receipt receipt) {
        return new InvoiceEmailResponse(receipt.invoiceNumber(), receipt.recipient(),
                receipt.sentAt(), receipt.fileName());
    }
}
