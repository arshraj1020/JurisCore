package com.juriscore.billing;

import com.juriscore.billing.api.dto.RecordPaymentRequest;
import com.juriscore.billing.domain.PaymentMethod;
import com.juriscore.billing.service.PaymentService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two bookkeepers, one invoice, the same moment.
 *
 * <p>The failure this exists to catch is invisible in a sequential test and invisible again
 * in a unit test with a mocked repository, because neither has a row lock. Both callers
 * read "nothing paid yet", both find their payment acceptable, both insert, and the invoice
 * is overpaid — with the second request reporting success. Phase 4 already taught this
 * codebase that a rule which passes against a mock may never have worked against
 * PostgreSQL; for money, that lesson is worth paying for with a real concurrent test.
 *
 * <p>What makes it correct is {@code PESSIMISTIC_WRITE} on the invoice row, taken before
 * the balance is read. The second caller waits, then reads the truth.
 */
class PaymentConcurrencyIT extends AbstractBillingIT {

    private static final int CONCURRENT_PAYERS = 6;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static void signInOnThisThread(UUID actor, UUID organizationId) {
        AuthenticatedUser caller = new AuthenticatedUser(actor, organizationId,
                "asha@sharma-legal.test", Role.FIRM_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority(Role.FIRM_ADMIN.authority()))));
    }

    /** @return true when the payment was accepted, false when it was refused */
    private boolean attempt(UUID invoiceId, UUID organizationId, UUID actor, String amount) {
        signInOnThisThread(actor, organizationId);
        try {
            new TransactionTemplate(transactionManager).execute(status ->
                    paymentService.record(invoiceId, organizationId,
                            new RecordPaymentRequest(new BigDecimal(amount), "INR", null,
                                    PaymentMethod.BANK_TRANSFER, null, null)));
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("six simultaneous payments of 2000 against an 11800 invoice: five land, one is refused")
    void concurrentPaymentsCannotOverpay() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        UUID organizationId = UUID.fromString(ledger.firm().id());
        UUID actor = userIdOf("asha@sharma-legal.test");
        UUID invoiceId = UUID.fromString(
                issued(ledger.firm().adminToken(), ledger.clientId(), null));

        // 11800 outstanding, six payers of 2000 each = 12000 attempted. Exactly five can
        // be accepted; the sixth would take the invoice to 12000 against a total of 11800.
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_PAYERS);
        try {
            List<Callable<Boolean>> payments = IntStream.range(0, CONCURRENT_PAYERS)
                    .mapToObj(i -> (Callable<Boolean>) () ->
                            attempt(invoiceId, organizationId, actor, "2000.00"))
                    .toList();

            List<Future<Boolean>> results = pool.invokeAll(payments, 60, TimeUnit.SECONDS);
            long accepted = results.stream().filter(PaymentConcurrencyIT::value).count();

            assertThat(accepted)
                    .as("five payments of 2000 fit inside 11800; the sixth must be refused")
                    .isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }

        String id = invoiceId.toString();
        assertThat(paidOn(id))
                .as("the recorded total must never exceed the invoice, whatever the timing")
                .isEqualByComparingTo("10000.00");
        assertThat(paidOn(id)).isLessThanOrEqualTo(totalOf(id));
        assertThat(paymentCount(id)).isEqualTo(5);
        assertThat(statusOf(id)).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @DisplayName("simultaneous payments that exactly settle an invoice leave it PAID once")
    void concurrentPaymentsThatSettleExactly() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        UUID organizationId = UUID.fromString(ledger.firm().id());
        UUID actor = userIdOf("asha@sharma-legal.test");
        UUID invoiceId = UUID.fromString(
                issued(ledger.firm().adminToken(), ledger.clientId(), null));

        // 11800 = 4 × 2950. Every payment is valid, and the last one settles it.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> payments = IntStream.range(0, 4)
                    .mapToObj(i -> (Callable<Boolean>) () ->
                            attempt(invoiceId, organizationId, actor, "2950.00"))
                    .toList();
            List<Future<Boolean>> results = pool.invokeAll(payments, 60, TimeUnit.SECONDS);

            assertThat(results.stream().filter(PaymentConcurrencyIT::value).count())
                    .as("every one of these is valid; serialising must not turn a valid "
                            + "payment into a conflict")
                    .isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }

        String id = invoiceId.toString();
        assertThat(paidOn(id)).isEqualByComparingTo("11800.00");
        assertThat(statusOf(id)).isEqualTo("PAID");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_at IS NOT NULL FROM billing.invoices WHERE id = ?::uuid",
                Boolean.class, id))
                .isTrue();
    }

    private static boolean value(Future<Boolean> future) {
        try {
            return Boolean.TRUE.equals(future.get());
        } catch (Exception e) {
            return false;
        }
    }
}
