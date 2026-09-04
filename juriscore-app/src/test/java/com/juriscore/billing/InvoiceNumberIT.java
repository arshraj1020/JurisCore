package com.juriscore.billing;

import com.juriscore.billing.api.dto.CreateInvoiceRequest;
import com.juriscore.billing.api.dto.InvoiceLineItemRequest;
import com.juriscore.billing.service.InvoiceService;
import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invoice numbering under concurrency.
 *
 * <p>The same test {@code CaseNumberIT} makes for matters, for the same reason and with
 * higher stakes: two matters sharing a number is embarrassing, two invoices sharing one is
 * an accounting problem. Contiguity matters as much as uniqueness — a firm whose invoices
 * run 1, 2, 4 has to explain where 3 went, and "the counter was read outside the lock" is
 * not an answer anybody wants to give an auditor.
 */
class InvoiceNumberIT extends AbstractBillingIT {

    private static final int CONCURRENT_WRITERS = 8;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("eight invoices raised at once get eight distinct, contiguous numbers")
    void concurrentCreationsNeverShareANumber() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        UUID organizationId = UUID.fromString(ledger.firm().id());
        UUID actor = userIdOf("asha@sharma-legal.test");
        UUID clientId = UUID.fromString(ledger.clientId());

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        try {
            List<Callable<String>> raisings = IntStream.range(0, CONCURRENT_WRITERS)
                    .mapToObj(i -> (Callable<String>) () -> {
                        signInOnThisThread(actor, organizationId);
                        try {
                            return transactions.execute(status -> invoiceService.create(
                                    organizationId,
                                    new CreateInvoiceRequest(clientId, null, "INR", null, null,
                                            null, "Invoice " + i,
                                            List.of(new InvoiceLineItemRequest("Advice",
                                                    new BigDecimal("1.000"),
                                                    new BigDecimal("1000.00"),
                                                    BigDecimal.ZERO))))
                                    .getInvoiceNumber());
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    })
                    .toList();

            List<Future<String>> results = pool.invokeAll(raisings, 60, TimeUnit.SECONDS);
            Set<String> numbers = results.stream()
                    .map(InvoiceNumberIT::value)
                    .collect(Collectors.toSet());

            assertThat(numbers)
                    .as("every concurrent invoice must have been given its own number")
                    .hasSize(CONCURRENT_WRITERS);

            int year = LocalDate.now(ZoneOffset.UTC).getYear();
            assertThat(numbers)
                    .as("and the run must be contiguous — a gap is a question an auditor asks")
                    .isEqualTo(IntStream.rangeClosed(1, CONCURRENT_WRITERS)
                            .mapToObj(n -> "INV-%d-%06d".formatted(year, n))
                            .collect(Collectors.toSet()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a rolled-back invoice does not burn a number")
    void aFailedCreationReleasesItsNumber() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        UUID organizationId = UUID.fromString(ledger.firm().id());
        UUID actor = userIdOf("asha@sharma-legal.test");
        UUID clientId = UUID.fromString(ledger.clientId());
        int year = LocalDate.now(ZoneOffset.UTC).getYear();

        signInOnThisThread(actor, organizationId);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        try {
            transactions.execute(status -> {
                String number = invoiceService.create(organizationId,
                        new CreateInvoiceRequest(clientId, null, "INR", null, null, null, null,
                                List.of(new InvoiceLineItemRequest("Advice",
                                        new BigDecimal("1.000"), new BigDecimal("100.00"),
                                        BigDecimal.ZERO))))
                        .getInvoiceNumber();
                assertThat(number).isEqualTo("INV-%d-000001".formatted(year));
                status.setRollbackOnly();
                return number;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.invoices", Long.class)).isZero();

        // The counter is released with the row it numbered, so the next invoice is 1 again
        // rather than 2 — no gap for the firm to explain.
        String next = draft(ledger.firm().adminToken(), ledger.clientId(), null);
        assertThat(numberOf(next)).isEqualTo("INV-%d-000001".formatted(year));
    }

    private static void signInOnThisThread(UUID actor, UUID organizationId) {
        AuthenticatedUser caller = new AuthenticatedUser(actor, organizationId,
                "asha@sharma-legal.test", Role.FIRM_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority(Role.FIRM_ADMIN.authority()))));
    }

    private static String value(Future<String> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("An invoice creation failed outright", e);
        }
    }
}
