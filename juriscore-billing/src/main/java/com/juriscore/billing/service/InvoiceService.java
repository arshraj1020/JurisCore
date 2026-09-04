package com.juriscore.billing.service;

import com.juriscore.billing.api.dto.CancelInvoiceRequest;
import com.juriscore.billing.api.dto.CreateInvoiceRequest;
import com.juriscore.billing.api.dto.IssueInvoiceRequest;
import com.juriscore.billing.api.dto.UpdateInvoiceRequest;
import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.billing.domain.InvoiceStatusPolicy;
import com.juriscore.billing.domain.Money;
import com.juriscore.billing.event.InvoiceCancelledEvent;
import com.juriscore.billing.event.InvoiceCreatedEvent;
import com.juriscore.billing.event.InvoiceIssuedEvent;
import com.juriscore.billing.repository.InvoiceRepository;
import com.juriscore.billing.repository.PaymentRepository;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.service.CaseAccess;
import com.juriscore.casework.service.ClientService;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Invoices: raising them, editing them while they are still drafts, sending them out and
 * withdrawing them.
 *
 * <p>Two rules shape everything below.
 *
 * <p><strong>Money is derived, never dictated.</strong> No request carries a subtotal, a
 * tax figure or a total, so there is nothing for a caller to over-state.
 * {@code InvoiceCalculator} computes all four from the lines, and
 * {@code ck_invoices_total} makes the database refuse a row where they do not add up.
 *
 * <p><strong>Issuing is a one-way door.</strong> A DRAFT is a working document and
 * everything about it may change. The moment it is issued it has been sent to somebody,
 * and its figures, its client and its matter are frozen — an edit that touches any of them
 * afterwards is a 409, not a quiet no-op, because a bookkeeper who thinks they corrected
 * an issued invoice must not be left believing it. Correcting an issued invoice means
 * cancelling it and raising another; Phase 5 has no credit note and half of one would be
 * worse than none.
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    /** Days from issue to due when neither the request nor the draft says. */
    private static final int DEFAULT_PAYMENT_TERM_DAYS = 30;

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final BillingProfileService billingProfiles;
    private final InvoiceNumberGenerator numberGenerator;
    private final InvoiceCalculator calculator;
    private final ClientService clientService;
    private final CaseAccess caseAccess;
    private final EventPublisher eventPublisher;

    // ------------------------------------------------------------------------ creating

    /**
     * Raises a draft.
     *
     * <p>Order matters. The client and matter are resolved first, so a request naming
     * another firm's client stops before a row exists and — more to the point — before an
     * invoice number is burned. Then the lines are priced, then the number is issued, then
     * the row is written. A rolled-back creation releases the counter with it, so the
     * firm's numbering has no gap.
     */
    @Transactional
    public Invoice create(UUID organizationId, CreateInvoiceRequest request) {
        clientService.requireSelectable(request.clientId(), organizationId);
        UUID caseId = requireCaseOfClient(request.caseId(), request.clientId(), organizationId);

        BillingProfile profile = billingProfiles.forOrganization(organizationId);
        String currency = request.currency() == null || request.currency().isBlank()
                ? profile.getDefaultCurrency()
                : CurrencyCodes.require(request.currency());

        requireDateOrder(request.issueDate(), request.dueDate());

        Invoice invoice = new Invoice();
        invoice.setOrganizationId(organizationId);
        invoice.setClientId(request.clientId());
        invoice.setCaseId(caseId);
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setCurrency(currency);
        invoice.setIssueDate(request.issueDate());
        invoice.setDueDate(request.dueDate());
        invoice.setNotes(request.notes() != null ? request.notes() : profile.getInvoiceNotes());

        calculator.applyLines(invoice, request.lineItems(), request.discountAmount());

        invoice.setInvoiceNumber(numberGenerator.nextFor(
                organizationId, profile.getInvoicePrefix(), today()));

        Invoice saved = invoiceRepository.save(invoice);

        log.info("Invoice {} raised for client {} in organization {}",
                saved.getInvoiceNumber(), saved.getClientId(), organizationId);
        eventPublisher.publish(new InvoiceCreatedEvent(organizationId, saved.getId(),
                saved.getInvoiceNumber(), saved.getClientId(), saved.getCaseId(),
                saved.getTotalAmount(), saved.getCurrency()));
        return saved;
    }

    // ------------------------------------------------------------------------- reading

    /** An invoice of this firm. Anything else answers not-found, never forbidden. */
    @Transactional(readOnly = true)
    public Invoice require(UUID invoiceId, UUID organizationId) {
        Invoice invoice = invoiceRepository.findByIdAndOrganizationId(invoiceId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.INVOICE_NOT_FOUND, invoiceId));
        TenantGuard.check(invoice, ErrorCode.INVOICE_NOT_FOUND);
        return invoice;
    }

    @Transactional(readOnly = true)
    public BigDecimal amountPaid(UUID invoiceId, UUID organizationId) {
        return Money.amount(paymentRepository.totalPaid(organizationId, invoiceId));
    }

    /**
     * The firm's invoices, newest first.
     *
     * <p>Branching over derived queries rather than one query with optional parameters:
     * {@code :status is null or i.status = :status} is a typing problem in Hibernate 6 and
     * a planner problem in PostgreSQL, and this codebase has avoided it everywhere else.
     */
    @Transactional(readOnly = true)
    public Page<Invoice> list(UUID organizationId, InvoiceStatus status, UUID clientId,
                              UUID caseId, Pageable pageable) {
        if (clientId != null) {
            clientService.requireSelectable(clientId, organizationId);
            return status == null
                    ? invoiceRepository.findByOrganizationIdAndClientIdOrderByCreatedAtDescIdDesc(
                            organizationId, clientId, pageable)
                    : invoiceRepository.findByOrganizationIdAndClientIdAndStatusOrderByCreatedAtDescIdDesc(
                            organizationId, clientId, status, pageable);
        }
        if (caseId != null) {
            caseAccess.require(caseId, organizationId);
            return status == null
                    ? invoiceRepository.findByOrganizationIdAndCaseIdOrderByCreatedAtDescIdDesc(
                            organizationId, caseId, pageable)
                    : invoiceRepository.findByOrganizationIdAndCaseIdAndStatusOrderByCreatedAtDescIdDesc(
                            organizationId, caseId, status, pageable);
        }
        return status == null
                ? invoiceRepository.findByOrganizationIdOrderByCreatedAtDescIdDesc(
                        organizationId, pageable)
                : invoiceRepository.findByOrganizationIdAndStatusOrderByCreatedAtDescIdDesc(
                        organizationId, status, pageable);
    }

    // ------------------------------------------------------------------------ updating

    /**
     * Edits an invoice, as far as its state allows.
     *
     * <p>On a DRAFT: everything. On anything else: {@code notes}, and a 409 if the request
     * asks for more. The check is on what was <em>sent</em>, not on what would change, so
     * resending an identical line list against an issued invoice is refused too — a caller
     * that believes it is editing frozen figures is wrong whether or not the values happen
     * to match.
     */
    @Transactional
    public Invoice update(UUID invoiceId, UUID organizationId, UpdateInvoiceRequest request) {
        Invoice invoice = require(invoiceId, organizationId);
        OptimisticVersion.require(invoice, request.version());

        if (!invoice.getStatus().isEditable()) {
            requireNothingFinancialIn(request, invoice.getStatus());
            invoice.setNotes(request.notes());
            return invoice;
        }

        UUID clientId = request.clientId() != null ? request.clientId() : invoice.getClientId();
        if (request.clientId() != null) {
            clientService.requireSelectable(clientId, organizationId);
        }
        UUID caseId = request.caseId() != null
                ? requireCaseOfClient(request.caseId(), clientId, organizationId)
                : revalidateExistingCase(invoice, clientId, organizationId, request.clientId() != null);

        requireDateOrder(request.issueDate(), request.dueDate());

        invoice.setClientId(clientId);
        invoice.setCaseId(caseId);
        invoice.setIssueDate(request.issueDate());
        invoice.setDueDate(request.dueDate());
        invoice.setNotes(request.notes());

        if (request.lineItems() != null) {
            calculator.applyLines(invoice, request.lineItems(), request.discountAmount());
        } else if (request.discountAmount() != null) {
            // Re-price with the existing lines so the discount bound is checked against
            // the same gross the database is about to check it against.
            calculator.applyLines(invoice,
                    invoice.getLineItems().stream().map(InvoiceCalculator::asRequest).toList(),
                    request.discountAmount());
        }
        return invoice;
    }

    // ------------------------------------------------------------------------ lifecycle

    /**
     * Sends a draft out.
     *
     * <p>Both dates become mandatory here, because {@code ck_invoices_issued_dates} makes
     * them mandatory for anything past DRAFT — an invoice with no due date has no moment at
     * which it is late, and the overdue sweep would never see it.
     */
    @Transactional
    public Invoice issue(UUID invoiceId, UUID organizationId, IssueInvoiceRequest request) {
        Invoice invoice = require(invoiceId, organizationId);
        OptimisticVersion.require(invoice, request.version());

        // The lifecycle answer first, before any check on the invoice's contents. Issuing
        // an invoice that has already gone out is a 409 whatever its lines look like, and
        // telling that caller their invoice "has no lines" would be true, unhelpful and
        // beside the point.
        InvoiceStatusPolicy.requireTransition(invoice.getStatus(), InvoiceStatus.ISSUED);

        LocalDate issueDate = firstNonNull(request.issueDate(), invoice.getIssueDate(),
                today());
        LocalDate dueDate = firstNonNull(request.dueDate(), invoice.getDueDate(),
                issueDate.plusDays(DEFAULT_PAYMENT_TERM_DAYS));
        requireDateOrder(issueDate, dueDate);

        if (invoice.getLineItems().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "An invoice with no lines cannot be issued");
        }

        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);
        invoice.transitionTo(InvoiceStatus.ISSUED, Instant.now());

        log.info("Invoice {} issued in organization {}, due {}",
                invoice.getInvoiceNumber(), organizationId, dueDate);
        eventPublisher.publish(new InvoiceIssuedEvent(organizationId, invoice.getId(),
                invoice.getInvoiceNumber(), invoice.getClientId(), invoice.getTotalAmount(),
                invoice.getCurrency(), dueDate));
        return invoice;
    }

    /**
     * Withdraws an invoice.
     *
     * <p>Allowed from DRAFT, ISSUED, PARTIALLY_PAID and OVERDUE; refused from PAID, which
     * is settled history. Payments already recorded stay attached and stay visible — a
     * cancellation says the invoice should not have been raised, not that the money never
     * arrived.
     */
    @Transactional
    public Invoice cancel(UUID invoiceId, UUID organizationId, CancelInvoiceRequest request) {
        Invoice invoice = require(invoiceId, organizationId);
        OptimisticVersion.require(invoice, request.version());

        invoice.transitionTo(InvoiceStatus.CANCELLED, Instant.now());
        if (request.reason() != null && !request.reason().isBlank()) {
            String existing = invoice.getNotes() == null ? "" : invoice.getNotes() + "\n";
            invoice.setNotes(truncate(existing + "Cancelled: " + request.reason().trim(), 2000));
        }

        log.info("Invoice {} cancelled in organization {}", invoice.getInvoiceNumber(), organizationId);
        eventPublisher.publish(new InvoiceCancelledEvent(organizationId, invoice.getId(),
                invoice.getInvoiceNumber(), invoice.getClientId()));
        return invoice;
    }

    // -------------------------------------------------------------------------- helpers

    /**
     * Resolves an optional matter and proves it belongs to the client being billed.
     *
     * <p>The second half is the one that matters. Both the client and the case are checked
     * against the caller's firm, so neither can come from another tenant — but a matter
     * belonging to a <em>different client of the same firm</em> would otherwise attach
     * cleanly, and an invoice that bills one client for another's matter is a mistake the
     * data model can prevent rather than one the firm has to notice.
     */
    private UUID requireCaseOfClient(UUID caseId, UUID clientId, UUID organizationId) {
        if (caseId == null) {
            return null;
        }
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        if (!legalCase.getClientId().equals(clientId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "That matter belongs to a different client");
        }
        return legalCase.getId();
    }

    /** Re-checks an already-attached matter when the draft's client changes underneath it. */
    private UUID revalidateExistingCase(Invoice invoice, UUID clientId, UUID organizationId,
                                        boolean clientChanged) {
        if (invoice.getCaseId() == null) {
            return null;
        }
        return clientChanged
                ? requireCaseOfClient(invoice.getCaseId(), clientId, organizationId)
                : invoice.getCaseId();
    }

    private void requireNothingFinancialIn(UpdateInvoiceRequest request, InvoiceStatus status) {
        boolean touchesFrozenFields = request.lineItems() != null
                || request.discountAmount() != null
                || request.clientId() != null
                || request.caseId() != null
                || request.issueDate() != null
                || request.dueDate() != null;
        if (touchesFrozenFields) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "An invoice in " + status + " is frozen. Only its notes can be edited; "
                            + "to change the figures, cancel it and raise a new one.");
        }
    }

    private void requireDateOrder(LocalDate issueDate, LocalDate dueDate) {
        if (issueDate != null && dueDate != null && dueDate.isBefore(issueDate)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A due date cannot come before the issue date");
        }
    }

    /**
     * Today, in UTC.
     *
     * <p>Deliberately explicit rather than {@code LocalDate.now()}, which reads the
     * server's default zone — so the year an invoice is numbered in, and the day it counts
     * as issued, would depend on which machine handled the request. Every timestamp column
     * in this schema is {@code TIMESTAMPTZ} and the case-number generator already takes
     * its year in UTC; this is the same decision for the same reason.
     */
    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
