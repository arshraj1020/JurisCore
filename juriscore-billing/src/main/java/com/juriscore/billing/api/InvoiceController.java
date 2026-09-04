package com.juriscore.billing.api;

import com.juriscore.billing.api.dto.CancelInvoiceRequest;
import com.juriscore.billing.api.dto.CreateInvoiceRequest;
import com.juriscore.billing.api.dto.InvoiceResponse;
import com.juriscore.billing.api.dto.IssueInvoiceRequest;
import com.juriscore.billing.api.dto.PaymentResponse;
import com.juriscore.billing.api.dto.RecordPaymentRequest;
import com.juriscore.billing.api.dto.UpdateInvoiceRequest;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.billing.service.InvoiceService;
import com.juriscore.billing.service.PaymentService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Invoices and the payments recorded against them.
 *
 * <h2>The role matrix, and why it is shaped this way</h2>
 *
 * <p>Reading and drafting are open to all staff; <strong>issuing, cancelling and recording
 * payments are the administrator's alone.</strong> A clerk preparing a draft is ordinary
 * case-maintenance work and matches what they already do with tasks and documents. Sending
 * a bill to a client, withdrawing one, and deciding that money has arrived are not: they
 * are the three actions where a mistake reaches the client or the firm's books, and the
 * platform already reserves its consequential verbs — deleting a client, removing a
 * document — for {@code FIRM_ADMIN}. A lawyer is not given them either. Nothing in the
 * repository says a fee earner administers billing, and quietly assuming it would broaden
 * a permission on a guess.
 *
 * <p>{@code CLIENT} reaches none of this. There is no client billing portal in Phase 5,
 * and an invoice referencing a client is a firm-side record about them, not a document
 * shared with them. {@code SUPER_ADMIN} reaches none of it either: it has no organization
 * of its own, so {@code CurrentUser.requireOrganizationId()} refuses it before any handler
 * body runs — a platform role must not read one firm's books by virtue of being a platform
 * role.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Billing a firm's clients, and recording what they pay")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    @PostMapping("/api/v1/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'CLERK')")
    @Operation(summary = "Raise a draft invoice",
            description = "Created as a DRAFT. The number, the totals and the tenant are all "
                    + "server-side; any subtotal or total in the body is ignored because there "
                    + "is no field for one.")
    public ApiResponse<InvoiceResponse> create(@Valid @RequestBody CreateInvoiceRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(view(invoiceService.create(organizationId, request), organizationId),
                "Invoice created successfully");
    }

    @GetMapping("/api/v1/invoices")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List the firm's invoices",
            description = "Newest first. Line items are omitted from a list — fetch one "
                    + "invoice to see them.")
    public ApiResponse<PageResponse<InvoiceResponse>> list(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) UUID caseId,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                invoiceService.list(organizationId, status, clientId, caseId, pageable),
                invoice -> InvoiceResponse.summary(invoice,
                        invoiceService.amountPaid(invoice.getId(), organizationId))));
    }

    @GetMapping("/api/v1/invoices/{invoiceId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one invoice, with its lines and its balance")
    public ApiResponse<InvoiceResponse> byId(@PathVariable UUID invoiceId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(view(invoiceService.require(invoiceId, organizationId), organizationId));
    }

    @PatchMapping("/api/v1/invoices/{invoiceId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'CLERK')")
    @Operation(summary = "Edit an invoice",
            description = "A DRAFT can be changed freely. Once issued only the notes may be "
                    + "edited, and sending anything financial is a 409 rather than a silent "
                    + "no-op. Send the version you last read.")
    public ApiResponse<InvoiceResponse> update(@PathVariable UUID invoiceId,
                                               @Valid @RequestBody UpdateInvoiceRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                view(invoiceService.update(invoiceId, organizationId, request), organizationId),
                "Invoice updated successfully");
    }

    @PostMapping("/api/v1/invoices/{invoiceId}/issue")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Issue an invoice",
            description = "One-way. From here the figures, the client and the matter are frozen.")
    public ApiResponse<InvoiceResponse> issue(@PathVariable UUID invoiceId,
                                              @Valid @RequestBody IssueInvoiceRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                view(invoiceService.issue(invoiceId, organizationId, request), organizationId),
                "Invoice issued successfully");
    }

    @PostMapping("/api/v1/invoices/{invoiceId}/cancel")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Withdraw an invoice",
            description = "Terminal. A settled invoice cannot be cancelled; payments already "
                    + "recorded against a cancelled one stay attached.")
    public ApiResponse<InvoiceResponse> cancel(@PathVariable UUID invoiceId,
                                               @Valid @RequestBody CancelInvoiceRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                view(invoiceService.cancel(invoiceId, organizationId, request), organizationId),
                "Invoice cancelled successfully");
    }

    @GetMapping("/api/v1/invoices/{invoiceId}/payments")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "What has been paid against this invoice")
    public ApiResponse<PageResponse<PaymentResponse>> payments(
            @PathVariable UUID invoiceId,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                paymentService.listForInvoice(invoiceId, organizationId, pageable),
                PaymentResponse::from));
    }

    @PostMapping("/api/v1/invoices/{invoiceId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Record a payment already received",
            description = "JurisCore takes no payments: this records one that happened "
                    + "elsewhere. The currency must match the invoice's — nothing is "
                    + "converted — and the total recorded can never exceed the invoice.")
    public ApiResponse<PaymentResponse> recordPayment(
            @PathVariable UUID invoiceId,
            @Valid @RequestBody RecordPaymentRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                PaymentResponse.from(paymentService.record(invoiceId, organizationId, request)),
                "Payment recorded successfully");
    }

    /** Every invoice goes out with its balance, so no caller has to derive one. */
    private InvoiceResponse view(Invoice invoice, UUID organizationId) {
        return InvoiceResponse.from(invoice,
                invoiceService.amountPaid(invoice.getId(), organizationId));
    }
}
