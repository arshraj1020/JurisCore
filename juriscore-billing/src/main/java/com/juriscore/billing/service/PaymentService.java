package com.juriscore.billing.service;

import com.juriscore.billing.api.dto.RecordPaymentRequest;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.billing.domain.Money;
import com.juriscore.billing.domain.Payment;
import com.juriscore.billing.event.InvoicePaidEvent;
import com.juriscore.billing.event.PaymentRecordedEvent;
import com.juriscore.billing.repository.InvoiceRepository;
import com.juriscore.billing.repository.PaymentRepository;
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
import java.util.UUID;

/**
 * Recording money against an invoice.
 *
 * <p>Recording, not taking. JurisCore is connected to no payment gateway, card network,
 * UPI handle or bank; a payment row says a person told the system that money arrived. The
 * method is a label, the reference is a cheque number or a bank narration, and no
 * credential is stored anywhere in this module.
 *
 * <h2>Why the invoice is locked first</h2>
 *
 * <p>Two bookkeepers recording payments on the same invoice at the same moment is not a
 * rare case, and the naive version of this method gets it wrong in a way that costs money.
 * Both read "0 of 10,000 paid", both find their 6,000 acceptable, both insert, and the
 * invoice has been overpaid by 2,000 — with the second transaction reporting success.
 *
 * <p>Optimistic locking does not fix it either. The version column would only object
 * because both transactions happen to write the invoice row, and the bookkeeper whose
 * perfectly valid payment lost the race would get a 409 telling them to reload — for a
 * payment that should simply have been accepted, had it been second in line rather than
 * simultaneous. Worse, it fixes nothing at all in the case where the second payment does
 * not change the status.
 *
 * <p>So the invoice row is taken under {@code PESSIMISTIC_WRITE} before anything is read.
 * The second caller waits for the first to commit and then reads the real balance, so an
 * overpayment is refused because it is an overpayment, and a valid concurrent payment
 * succeeds because it is valid. The lock is held to commit, which covers the balance read,
 * the payment insert and the status transition together — the three things that must agree.
 *
 * <p>Deadlock is not a concern: only ever one invoice row is locked, and always the one
 * being paid, so there is no pair of transactions holding what the other wants.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final EventPublisher eventPublisher;

    /**
     * Records a payment and moves the invoice to whatever its new balance says it is.
     *
     * <p>Publishes {@code payment.recorded} always, and {@code invoice.paid} as well when
     * this payment settles it. Two events rather than one because a consumer usually cares
     * about exactly one of "money arrived" and "this invoice is finished".
     */
    @Transactional
    public Payment record(UUID invoiceId, UUID organizationId, RecordPaymentRequest request) {
        // The lock comes first, before any decision is made from what it protects.
        Invoice invoice = invoiceRepository
                .findByIdAndOrganizationIdForUpdate(invoiceId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.INVOICE_NOT_FOUND, invoiceId));
        TenantGuard.check(invoice, ErrorCode.INVOICE_NOT_FOUND);

        if (!invoice.getStatus().acceptsPayment()) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    invoice.getStatus() == InvoiceStatus.DRAFT
                            ? "A draft has not been sent to anybody yet, so it cannot be paid. "
                                    + "Issue it first."
                            : "An invoice in " + invoice.getStatus() + " cannot receive a payment");
        }

        BigDecimal amount = Money.amount(request.amount());
        if (!Money.isPositive(amount)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A payment must be greater than zero");
        }

        String currency = request.currency() == null || request.currency().isBlank()
                ? invoice.getCurrency()
                : CurrencyCodes.require(request.currency());
        if (!currency.equals(invoice.getCurrency())) {
            // Refused, never converted. A conversion needs a rate, a date and a source,
            // and JurisCore has none of the three — see CurrencyCodes.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This invoice is in " + invoice.getCurrency() + ". Record the payment in "
                            + "that currency; JurisCore does not convert.");
        }

        BigDecimal alreadyPaid = Money.amount(paymentRepository.totalPaid(organizationId, invoiceId));
        BigDecimal outstanding = invoice.getTotalAmount().subtract(alreadyPaid);
        if (amount.compareTo(outstanding) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "That is more than the " + outstanding + " " + invoice.getCurrency()
                            + " outstanding on this invoice");
        }

        Payment payment = new Payment();
        payment.setOrganizationId(organizationId);
        payment.setInvoiceId(invoiceId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setPaymentDate(request.paymentDate() != null
                ? request.paymentDate() : InvoiceService.today());
        payment.setMethod(request.method());
        payment.setReference(request.reference());
        payment.setNotes(request.notes());
        Payment saved = paymentRepository.saveAndFlush(payment);

        BigDecimal paidNow = alreadyPaid.add(amount);
        BigDecimal dueNow = invoice.getTotalAmount().subtract(paidNow);
        boolean settled = dueNow.signum() == 0;

        InvoiceStatus target = settled ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID;
        if (invoice.getStatus() != target) {
            invoice.transitionTo(target, Instant.now());
        }

        // Amount and balance only. No reference, no note, no bank narration: nothing in
        // this system consumes them, and an event bus is a poor place to widen what leaks.
        log.info("Payment of {} {} recorded against invoice {} in organization {}; {} outstanding",
                amount, currency, invoice.getInvoiceNumber(), organizationId, dueNow);
        eventPublisher.publish(new PaymentRecordedEvent(organizationId, saved.getId(), invoiceId,
                invoice.getInvoiceNumber(), amount, currency, dueNow));
        if (settled) {
            eventPublisher.publish(new InvoicePaidEvent(organizationId, invoiceId,
                    invoice.getInvoiceNumber(), invoice.getClientId(), invoice.getTotalAmount(),
                    invoice.getCurrency()));
        }
        return saved;
    }

    /** The payments on one invoice, most recent first. Tenant-scoped like everything else. */
    @Transactional(readOnly = true)
    public Page<Payment> listForInvoice(UUID invoiceId, UUID organizationId, Pageable pageable) {
        Invoice invoice = invoiceRepository.findByIdAndOrganizationId(invoiceId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.INVOICE_NOT_FOUND, invoiceId));
        TenantGuard.check(invoice, ErrorCode.INVOICE_NOT_FOUND);
        return paymentRepository.findByOrganizationIdAndInvoiceIdOrderByPaymentDateDescIdDesc(
                organizationId, invoiceId, pageable);
    }

    /** Convenience for callers that need the figure without the rows. */
    @Transactional(readOnly = true)
    public BigDecimal totalPaid(UUID invoiceId, UUID organizationId) {
        return Money.amount(paymentRepository.totalPaid(organizationId, invoiceId));
    }
}
