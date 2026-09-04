package com.juriscore.billing.service;

import com.juriscore.billing.api.dto.CancelInvoiceRequest;
import com.juriscore.billing.api.dto.CreateInvoiceRequest;
import com.juriscore.billing.api.dto.InvoiceLineItemRequest;
import com.juriscore.billing.api.dto.IssueInvoiceRequest;
import com.juriscore.billing.api.dto.UpdateInvoiceRequest;
import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.billing.event.InvoiceCancelledEvent;
import com.juriscore.billing.event.InvoiceCreatedEvent;
import com.juriscore.billing.event.InvoiceIssuedEvent;
import com.juriscore.billing.repository.InvoiceRepository;
import com.juriscore.billing.repository.PaymentRepository;
import com.juriscore.billing.support.CallerContext;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.service.CaseAccess;
import com.juriscore.casework.service.ClientService;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Invoice creation, editing and lifecycle, with everything below the service mocked out. */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();
    private static final UUID OTHER_CLIENT_ID = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BillingProfileService billingProfiles;
    @Mock
    private InvoiceNumberGenerator numberGenerator;
    @Spy
    private InvoiceCalculator calculator = new InvoiceCalculator();
    @Mock
    private ClientService clientService;
    @Mock
    private CaseAccess caseAccess;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private InvoiceService invoiceService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(ACTOR, FIRM, Role.FIRM_ADMIN);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static BillingProfile profile() {
        BillingProfile profile = new BillingProfile();
        profile.setOrganizationId(FIRM);
        profile.setDefaultCurrency("INR");
        profile.setInvoicePrefix("INV");
        return profile;
    }

    private static LegalCase legalCase(UUID clientId) {
        LegalCase legalCase = new LegalCase();
        legalCase.setId(CASE_ID);
        legalCase.setOrganizationId(FIRM);
        legalCase.setCaseNumber("CASE-2026-000001");
        legalCase.setTitle("Menon v. Iyer");
        legalCase.setClientId(clientId);
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(Instant.now());
        return legalCase;
    }

    private static InvoiceLineItemRequest line() {
        return new InvoiceLineItemRequest("Drafting", new BigDecimal("2.500"),
                new BigDecimal("4000.00"), new BigDecimal("18.000"));
    }

    private static CreateInvoiceRequest creation(UUID caseId) {
        return new CreateInvoiceRequest(CLIENT_ID, caseId, null, null, null, null, null,
                List.of(line()));
    }

    private void readyToCreate() {
        when(billingProfiles.forOrganization(FIRM)).thenReturn(profile());
        when(numberGenerator.nextFor(eq(FIRM), anyString(), any())).thenReturn("INV-2026-000001");
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(call -> {
            Invoice i = call.getArgument(0);
            if (i.getId() == null) {
                i.setId(INVOICE_ID);
            }
            return i;
        });
    }

    private static Invoice stored(InvoiceStatus status) {
        Invoice invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setOrganizationId(FIRM);
        invoice.setClientId(CLIENT_ID);
        invoice.setInvoiceNumber("INV-2026-000001");
        invoice.setStatus(status);
        invoice.setCurrency("INR");
        invoice.setSubtotal(new BigDecimal("10000.00"));
        invoice.setTaxAmount(new BigDecimal("1800.00"));
        invoice.setTotalAmount(new BigDecimal("11800.00"));
        if (status != InvoiceStatus.DRAFT) {
            invoice.setIssueDate(LocalDate.of(2026, 3, 1));
            invoice.setDueDate(LocalDate.of(2026, 3, 31));
        }
        return invoice;
    }

    private void found(Invoice invoice) {
        when(invoiceRepository.findByIdAndOrganizationId(INVOICE_ID, FIRM))
                .thenReturn(Optional.of(invoice));
    }

    // ------------------------------------------------------------------------ creating

    @Test
    void createsADraftWithServerSideTotals() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(null);
        readyToCreate();

        Invoice invoice = invoiceService.create(FIRM, creation(null));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.getOrganizationId()).isEqualTo(FIRM);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-2026-000001");
        assertThat(invoice.getCurrency()).isEqualTo("INR");
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("10000.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("1800.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("11800.00");
        assertThat(invoice.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("a matter may be attached when it belongs to the client being billed")
    void attachesAMatterOfTheSameClient() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(null);
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase(CLIENT_ID));
        readyToCreate();

        assertThat(invoiceService.create(FIRM, creation(CASE_ID)).getCaseId()).isEqualTo(CASE_ID);
    }

    @Test
    @DisplayName("a matter of a different client of the same firm is refused")
    void refusesAMatterBelongingToAnotherClient() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(null);
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase(OTHER_CLIENT_ID));

        assertThatThrownBy(() -> invoiceService.create(FIRM, creation(CASE_ID)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different client");

        verify(invoiceRepository, never()).save(any());
        verify(numberGenerator, never()).nextFor(any(), anyString(), any());
    }

    @Test
    @DisplayName("a foreign client stops the request before an invoice number is burned")
    void refusesAForeignClientBeforeNumbering() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM))
                .thenThrow(new ApiException(ErrorCode.CLIENT_NOT_FOUND));

        assertThatThrownBy(() -> invoiceService.create(FIRM, creation(null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);

        verify(numberGenerator, never()).nextFor(any(), anyString(), any());
        verify(invoiceRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void refusesADueDateBeforeTheIssueDate() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(null);
        when(billingProfiles.forOrganization(FIRM)).thenReturn(profile());

        assertThatThrownBy(() -> invoiceService.create(FIRM, new CreateInvoiceRequest(
                CLIENT_ID, null, "INR", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1),
                null, null, List.of(line()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("before the issue date");
    }

    @Test
    void refusesAnUnknownCurrency() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(null);
        when(billingProfiles.forOrganization(FIRM)).thenReturn(profile());

        assertThatThrownBy(() -> invoiceService.create(FIRM, new CreateInvoiceRequest(
                CLIENT_ID, null, "XYZ", null, null, null, null, List.of(line()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    void publishesInvoiceCreated() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(null);
        readyToCreate();

        invoiceService.create(FIRM, creation(null));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(InvoiceCreatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("invoice.created");
        assertThat(((InvoiceCreatedEvent) published.getValue()).getTotalAmount())
                .isEqualByComparingTo("11800.00");
    }

    // ------------------------------------------------------------------------ updating

    @Test
    void editsADraftFreely() {
        Invoice invoice = stored(InvoiceStatus.DRAFT);
        found(invoice);

        invoiceService.update(INVOICE_ID, FIRM, new UpdateInvoiceRequest(0L, null, null,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), new BigDecimal("500.00"),
                "Revised", List.of(line())));

        assertThat(invoice.getNotes()).isEqualTo("Revised");
        assertThat(invoice.getIssueDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("500.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("11300.00");
    }

    @Test
    @DisplayName("an issued invoice takes a note and refuses everything else with a 409")
    void anIssuedInvoiceIsFrozen() {
        Invoice invoice = stored(InvoiceStatus.ISSUED);
        found(invoice);

        invoiceService.update(INVOICE_ID, FIRM, new UpdateInvoiceRequest(0L, null, null, null,
                null, null, "Chased by phone", null));
        assertThat(invoice.getNotes()).isEqualTo("Chased by phone");

        for (UpdateInvoiceRequest attempt : List.of(
                new UpdateInvoiceRequest(0L, null, null, null, null, null, null, List.of(line())),
                new UpdateInvoiceRequest(0L, null, null, null, null, new BigDecimal("1.00"), null, null),
                new UpdateInvoiceRequest(0L, OTHER_CLIENT_ID, null, null, null, null, null, null),
                new UpdateInvoiceRequest(0L, null, CASE_ID, null, null, null, null, null),
                new UpdateInvoiceRequest(0L, null, null, LocalDate.of(2026, 5, 1), null, null, null, null))) {
            assertThatThrownBy(() -> invoiceService.update(INVOICE_ID, FIRM, attempt))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
        }

        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("11800.00");
        assertThat(invoice.getClientId()).isEqualTo(CLIENT_ID);
    }

    @Test
    @DisplayName("a stale version is a 409, as everywhere else in the platform")
    void refusesAStaleVersion() {
        found(stored(InvoiceStatus.DRAFT));

        assertThatThrownBy(() -> invoiceService.update(INVOICE_ID, FIRM,
                new UpdateInvoiceRequest(7L, null, null, null, null, null, "x", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    void refusesAMissingVersion() {
        found(stored(InvoiceStatus.DRAFT));

        assertThatThrownBy(() -> invoiceService.update(INVOICE_ID, FIRM,
                new UpdateInvoiceRequest(null, null, null, null, null, null, "x", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    // ----------------------------------------------------------------------- lifecycle

    @Test
    void issuesADraftAndStampsBothDates() {
        Invoice invoice = stored(InvoiceStatus.DRAFT);
        invoice.addLineItem(new com.juriscore.billing.domain.InvoiceLineItem());
        found(invoice);

        invoiceService.issue(INVOICE_ID, FIRM, new IssueInvoiceRequest(0L,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoice.getIssueDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(invoice.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("issuing with no dates defaults to today and thirty days")
    void issuingDefaultsTheDates() {
        Invoice invoice = stored(InvoiceStatus.DRAFT);
        invoice.addLineItem(new com.juriscore.billing.domain.InvoiceLineItem());
        found(invoice);

        invoiceService.issue(INVOICE_ID, FIRM, new IssueInvoiceRequest(0L, null, null));

        assertThat(invoice.getIssueDate()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(invoice.getDueDate()).isEqualTo(invoice.getIssueDate().plusDays(30));
    }

    @Test
    void refusesToIssueAnInvoiceWithNoLines() {
        found(stored(InvoiceStatus.DRAFT));

        assertThatThrownBy(() -> invoiceService.issue(INVOICE_ID, FIRM,
                new IssueInvoiceRequest(0L, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no lines");
    }

    @Test
    void refusesToIssueTwice() {
        found(stored(InvoiceStatus.ISSUED));

        assertThatThrownBy(() -> invoiceService.issue(INVOICE_ID, FIRM,
                new IssueInvoiceRequest(0L, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void publishesInvoiceIssued() {
        Invoice invoice = stored(InvoiceStatus.DRAFT);
        invoice.addLineItem(new com.juriscore.billing.domain.InvoiceLineItem());
        found(invoice);

        invoiceService.issue(INVOICE_ID, FIRM, new IssueInvoiceRequest(0L,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        InvoiceIssuedEvent event = (InvoiceIssuedEvent) published.getValue();
        assertThat(event.eventType()).isEqualTo("invoice.issued");
        assertThat(event.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void cancelsAndRecordsTheReason() {
        Invoice invoice = stored(InvoiceStatus.ISSUED);
        found(invoice);

        invoiceService.cancel(INVOICE_ID, FIRM, new CancelInvoiceRequest(0L, "Raised in error"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(invoice.getCancelledAt()).isNotNull();
        assertThat(invoice.getNotes()).contains("Cancelled: Raised in error");

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(InvoiceCancelledEvent.class);
    }

    @Test
    @DisplayName("a settled invoice cannot be cancelled — that would be rewriting history")
    void refusesToCancelASettledInvoice() {
        found(stored(InvoiceStatus.PAID));

        assertThatThrownBy(() -> invoiceService.cancel(INVOICE_ID, FIRM,
                new CancelInvoiceRequest(0L, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("a draft may be cancelled: an invoice number is already burned, so there is no delete")
    void cancelsADraft() {
        Invoice invoice = stored(InvoiceStatus.DRAFT);
        found(invoice);

        invoiceService.cancel(INVOICE_ID, FIRM, new CancelInvoiceRequest(0L, null));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    // ------------------------------------------------------------------------- reading

    @Test
    void aForeignInvoiceIsNotFound() {
        when(invoiceRepository.findByIdAndOrganizationId(INVOICE_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.require(INVOICE_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVOICE_NOT_FOUND);
    }
}
