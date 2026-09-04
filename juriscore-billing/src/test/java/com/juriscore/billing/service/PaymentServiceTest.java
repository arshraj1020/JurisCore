package com.juriscore.billing.service;

import com.juriscore.billing.api.dto.RecordPaymentRequest;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.billing.domain.Payment;
import com.juriscore.billing.domain.PaymentMethod;
import com.juriscore.billing.event.InvoicePaidEvent;
import com.juriscore.billing.event.PaymentRecordedEvent;
import com.juriscore.billing.repository.InvoiceRepository;
import com.juriscore.billing.repository.PaymentRepository;
import com.juriscore.billing.support.CallerContext;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.DomainEvent;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payment rules.
 *
 * <p>What these cannot show is the concurrency guarantee: a mocked repository has no row
 * lock, so "two payments cannot overpay" is asserted against a real database with real
 * concurrent transactions in {@code PaymentConcurrencyIT}. That split is deliberate — it is
 * the same lesson Phase 4 learned the hard way, where a rule that passed against a mock
 * had never worked against PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(ACTOR, FIRM, Role.FIRM_ADMIN);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static Invoice invoice(InvoiceStatus status, String total) {
        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setOrganizationId(FIRM);
        invoice.setClientId(CLIENT_ID);
        invoice.setInvoiceNumber("INV-2026-000001");
        invoice.setStatus(status);
        invoice.setCurrency("INR");
        invoice.setSubtotal(new BigDecimal(total));
        invoice.setTotalAmount(new BigDecimal(total));
        return invoice;
    }

    private void locked(Invoice invoice) {
        when(invoiceRepository.findByIdAndOrganizationIdForUpdate(INVOICE_ID, FIRM))
                .thenReturn(Optional.of(invoice));
    }

    private void alreadyPaid(String amount) {
        when(paymentRepository.totalPaid(FIRM, INVOICE_ID)).thenReturn(new BigDecimal(amount));
    }

    private void savesWhatItIsGiven() {
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(call -> {
            Payment p = call.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
    }

    private static RecordPaymentRequest payment(String amount) {
        return new RecordPaymentRequest(new BigDecimal(amount), "INR", LocalDate.of(2026, 3, 5),
                PaymentMethod.UPI, "UTR 220414512345", null);
    }

    // --------------------------------------------------------------------- happy paths

    @Test
    @DisplayName("a part payment moves the invoice to PARTIALLY_PAID")
    void partPaymentMovesToPartiallyPaid() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "10000.00");
        locked(invoice);
        alreadyPaid("0.00");
        savesWhatItIsGiven();

        Payment recorded = paymentService.record(INVOICE_ID, FIRM, payment("4000.00"));

        assertThat(recorded.getAmount()).isEqualByComparingTo("4000.00");
        assertThat(recorded.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(invoice.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("a payment that settles the balance moves the invoice to PAID and stamps paid_at")
    void fullPaymentSettles() {
        Invoice invoice = invoice(InvoiceStatus.PARTIALLY_PAID, "10000.00");
        locked(invoice);
        alreadyPaid("4000.00");
        savesWhatItIsGiven();

        paymentService.record(INVOICE_ID, FIRM, payment("6000.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("an overdue invoice can still be paid — money arrives late, and usually does")
    void overdueInvoicesStillAcceptPayment() {
        Invoice invoice = invoice(InvoiceStatus.OVERDUE, "10000.00");
        locked(invoice);
        alreadyPaid("0.00");
        savesWhatItIsGiven();

        paymentService.record(INVOICE_ID, FIRM, payment("10000.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("a second part payment leaves the status alone rather than re-transitioning")
    void secondPartPaymentIsNotATransition() {
        Invoice invoice = invoice(InvoiceStatus.PARTIALLY_PAID, "10000.00");
        locked(invoice);
        alreadyPaid("2000.00");
        savesWhatItIsGiven();

        // PARTIALLY_PAID -> PARTIALLY_PAID is refused by the policy, so the service must
        // not attempt it. If it did, this would throw.
        paymentService.record(INVOICE_ID, FIRM, payment("3000.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    void defaultsTheCurrencyAndTheDateToSomethingSensible() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "500.00");
        locked(invoice);
        alreadyPaid("0.00");
        savesWhatItIsGiven();

        Payment recorded = paymentService.record(INVOICE_ID, FIRM, new RecordPaymentRequest(
                new BigDecimal("500.00"), null, null, PaymentMethod.CASH, null, null));

        assertThat(recorded.getCurrency()).isEqualTo("INR");
        assertThat(recorded.getPaymentDate()).isNotNull();
    }

    // ----------------------------------------------------------------------- refusals

    @Test
    @DisplayName("overpayment is refused, down to the last paisa")
    void refusesOverpayment() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "10000.00");
        locked(invoice);
        alreadyPaid("9999.99");

        assertThatThrownBy(() -> paymentService.record(INVOICE_ID, FIRM, payment("0.02")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verify(paymentRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(any());
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("paying the exact remaining paisa is accepted — the boundary is inclusive")
    void theLastPaisaIsPayable() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "10000.00");
        locked(invoice);
        alreadyPaid("9999.99");
        savesWhatItIsGiven();

        paymentService.record(INVOICE_ID, FIRM, payment("0.01"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("a payment in another currency is refused, never converted")
    void refusesACurrencyMismatch() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "10000.00");
        locked(invoice);

        assertThatThrownBy(() -> paymentService.record(INVOICE_ID, FIRM,
                new RecordPaymentRequest(new BigDecimal("100.00"), "USD", null,
                        PaymentMethod.BANK_TRANSFER, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not convert");

        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void refusesADraft() {
        locked(invoice(InvoiceStatus.DRAFT, "10000.00"));

        assertThatThrownBy(() -> paymentService.record(INVOICE_ID, FIRM, payment("100.00")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void refusesACancelledInvoice() {
        locked(invoice(InvoiceStatus.CANCELLED, "10000.00"));

        assertThatThrownBy(() -> paymentService.record(INVOICE_ID, FIRM, payment("100.00")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("a settled invoice takes no further money")
    void refusesAPaidInvoice() {
        locked(invoice(InvoiceStatus.PAID, "10000.00"));

        assertThatThrownBy(() -> paymentService.record(INVOICE_ID, FIRM, payment("1.00")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void refusesAZeroPayment() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "10000.00");
        locked(invoice);

        assertThatThrownBy(() -> paymentService.record(INVOICE_ID, FIRM, payment("0.00")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    @DisplayName("another firm's invoice is not found, never forbidden")
    void aForeignInvoiceIsNotFound() {
        when(invoiceRepository.findByIdAndOrganizationIdForUpdate(INVOICE_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.record(INVOICE_ID, FIRM, payment("100.00")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVOICE_NOT_FOUND);
    }

    // ------------------------------------------------------------------------- events

    @Test
    @DisplayName("a part payment publishes payment.recorded and nothing else")
    void partPaymentPublishesOneEvent() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "10000.00");
        locked(invoice);
        alreadyPaid("0.00");
        savesWhatItIsGiven();

        paymentService.record(INVOICE_ID, FIRM, payment("4000.00"));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        PaymentRecordedEvent event = (PaymentRecordedEvent) published.getValue();
        assertThat(event.eventType()).isEqualTo("payment.recorded");
        assertThat(event.getAmount()).isEqualByComparingTo("4000.00");
        assertThat(event.getAmountDue()).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("settling publishes both payment.recorded and invoice.paid")
    void settlingPublishesTwoEvents() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "1000.00");
        locked(invoice);
        alreadyPaid("0.00");
        savesWhatItIsGiven();

        paymentService.record(INVOICE_ID, FIRM, payment("1000.00"));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publish(published.capture());
        List<DomainEvent> events = published.getAllValues();
        assertThat(events).extracting(DomainEvent::eventType)
                .containsExactly("payment.recorded", "invoice.paid");
        assertThat(((InvoicePaidEvent) events.get(1)).getTotalAmount())
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("the cheque number never leaves the database — it is not on the event")
    void theReferenceIsNotPublished() {
        Invoice invoice = invoice(InvoiceStatus.ISSUED, "1000.00");
        locked(invoice);
        alreadyPaid("0.00");
        savesWhatItIsGiven();

        paymentService.record(INVOICE_ID, FIRM, payment("500.00"));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().toString()).doesNotContain("UTR 220414512345");
    }
}
