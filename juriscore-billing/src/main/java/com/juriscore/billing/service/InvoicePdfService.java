package com.juriscore.billing.service;

import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.event.InvoicePdfDownloadedEvent;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.service.CaseAccess;
import com.juriscore.casework.service.ClientService;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.organization.domain.Organization;
import com.juriscore.organization.service.OrganizationService;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Renders an invoice as a PDF, in the firm's own identity.
 *
 * <h2>Regenerated on demand, never stored</h2>
 *
 * <p>An invoice's figures are frozen the moment it is issued ({@code Invoice#transitionTo}
 * refuses anything else), so a PDF built from them is a pure, deterministic function of
 * the invoice, the firm's current billing profile, the client and the matter — build it
 * from the same rows twice and it renders the same document twice. That means there is
 * nothing to keep in sync and nothing to invalidate: this service produces bytes and hands
 * them back, and no table in this schema remembers that it did. If a later phase needs
 * proof of exactly what bytes a client received on a given date, that is a deliberate new
 * feature (a stored, versioned snapshot) — not something this service quietly grows into.
 *
 * <h2>Whose identity the document carries</h2>
 *
 * <p>{@code BillingProfile} is the firm's own commercial identity and is preferred
 * throughout. {@code Organization} — the firm's platform-level record — is used only to
 * fill in what the profile has not set (name, contact email/phone, postal address), so a
 * firm that opens this feature before ever visiting billing settings still gets an invoice
 * that says who it is from, not one that says "JurisCore". Nothing here ever prints this
 * product's own name as the issuer.
 *
 * <h2>Missing optional fields</h2>
 *
 * <p>Every firm/client detail below is optional somewhere in the schema, and the template
 * omits a row rather than printing "null" or an empty label when one is absent — the same
 * rule the frontend already follows for a due date that has not been set.
 */
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private static final Logger log = LoggerFactory.getLogger(InvoicePdfService.class);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US);

    private final InvoiceService invoiceService;
    private final BillingProfileService billingProfileService;
    private final OrganizationService organizationService;
    private final ClientService clientService;
    private final CaseAccess caseAccess;
    private final EventPublisher eventPublisher;

    /** What a caller downloading an invoice's PDF gets back. */
    public record RenderedInvoice(byte[] bytes, String fileName) {
    }

    /**
     * Renders the invoice and records that it was downloaded.
     *
     * <p>Read-only: nothing about the invoice, the firm or the client changes because a
     * PDF of it was produced. The audit event is published from inside this transaction
     * and delivered {@code AFTER_COMMIT} like every other domain event in the platform —
     * see {@code AuditTrail} for why that ordering matters.
     */
    @Transactional(readOnly = true)
    public RenderedInvoice forDownload(UUID invoiceId, UUID organizationId) {
        Invoice invoice = invoiceService.require(invoiceId, organizationId);
        BillingProfile profile = billingProfileService.forOrganization(organizationId);
        Organization organization = organizationService.getById(organizationId);
        Client client = clientService.getScoped(invoice.getClientId(), organizationId);
        LegalCase legalCase = invoice.getCaseId() == null
                ? null
                : caseAccess.require(invoice.getCaseId(), organizationId);
        BigDecimal amountPaid = invoiceService.amountPaid(invoiceId, organizationId);

        byte[] pdf = render(invoice, profile, organization, client, legalCase, amountPaid);

        eventPublisher.publish(new InvoicePdfDownloadedEvent(organizationId, invoice.getId(),
                invoice.getInvoiceNumber(), invoice.getClientId()));
        log.info("Invoice {} PDF rendered ({} bytes) for organization {}",
                invoice.getInvoiceNumber(), pdf.length, organizationId);

        return new RenderedInvoice(pdf, fileName(invoice));
    }

    // -------------------------------------------------------------------------- rendering

    private byte[] render(Invoice invoice, BillingProfile profile, Organization organization,
                          Client client, LegalCase legalCase, BigDecimal amountPaid) {
        String html = InvoiceHtmlTemplate.render(invoice, profile, organization, client,
                legalCase, amountPaid);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, "");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            // A layout/parse failure here is a defect in the template this service
            // builds, not something a caller did — the same reasoning
            // GlobalExceptionHandler applies to any unexpected failure.
            log.error("Failed to render PDF for invoice {}", invoice.getInvoiceNumber(), e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "The invoice PDF could not be generated. Please try again.");
        }
    }

    private String fileName(Invoice invoice) {
        String safeNumber = invoice.getInvoiceNumber().replaceAll("[^A-Za-z0-9-]", "-");
        return "invoice-" + safeNumber + ".pdf";
    }

    // ---------------------------------------------------------------- shared formatting

    /** {@code 11,800.00 INR} — grouped, two decimals, currency code trailing. Never the
     * server's default locale: see {@code InvoiceService#today()} for why that matters. */
    static String money(BigDecimal amount, String currency) {
        DecimalFormat format = new DecimalFormat("#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(amount) + " " + currency;
    }

    static String date(LocalDate value) {
        return value == null ? null : value.format(DATE_FORMAT);
    }

    static String quantity(BigDecimal value) {
        return value.stripTrailingZeros().scale() < 0
                ? value.setScale(0).toPlainString()
                : value.stripTrailingZeros().toPlainString();
    }

    static String percent(BigDecimal rate) {
        return quantity(rate) + "%";
    }
}
