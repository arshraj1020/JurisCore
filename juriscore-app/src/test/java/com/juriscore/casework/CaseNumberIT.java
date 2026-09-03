package com.juriscore.casework;

import com.juriscore.casework.api.dto.CreateCaseRequest;
import com.juriscore.casework.service.CaseService;
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
 * Case numbering under concurrency.
 *
 * <p>The interesting failure is invisible in a sequential test: "read the highest number,
 * add one, insert" works perfectly until two people open a matter in the same second, at
 * which point one of them either fails on the unique index or — without the index —
 * quietly gets a duplicate. So this opens several matters at once, from separate threads
 * in separate transactions, and insists every number came out distinct and contiguous.
 *
 * <p>Contiguity matters as much as uniqueness. A firm that numbers its matters 1, 2, 4
 * has to explain where 3 went, and "the counter was read outside the lock" is not an
 * answer anybody wants to give an auditor.
 */
class CaseNumberIT extends AbstractCaseworkIT {

    private static final int CONCURRENT_WRITERS = 8;

    @Autowired
    private CaseService caseService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("eight matters opened at once get eight distinct, contiguous numbers")
    void concurrentCreationsNeverShareANumber() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        UUID organizationId = UUID.fromString(firm.id());
        UUID actor = userIdOf("asha@sharma-legal.test");
        UUID clientId = UUID.fromString(
                createClient(firm.adminToken(), "Asha Menon", "asha@menon.test"));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        try {
            List<Callable<String>> openings = IntStream.range(0, CONCURRENT_WRITERS)
                    .mapToObj(i -> (Callable<String>) () -> {
                        signInOnThisThread(actor, organizationId);
                        try {
                            return transactions.execute(status -> caseService.create(
                                    organizationId, actor,
                                    new CreateCaseRequest("Matter " + i, null, clientId))
                                    .getCaseNumber());
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    })
                    .toList();

            List<Future<String>> results = pool.invokeAll(openings, 60, TimeUnit.SECONDS);
            Set<String> numbers = results.stream()
                    .map(CaseNumberIT::value)
                    .collect(Collectors.toSet());

            assertThat(numbers)
                    .as("every concurrent opening must have been given its own number")
                    .hasSize(CONCURRENT_WRITERS);

            int year = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear();
            Set<String> expected = IntStream.rangeClosed(1, CONCURRENT_WRITERS)
                    .mapToObj(n -> "CASE-%d-%06d".formatted(year, n))
                    .collect(Collectors.toSet());
            assertThat(numbers)
                    .as("and the run must have no gaps — a gap means a counter was read "
                            + "outside the row lock")
                    .isEqualTo(expected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the counter is per firm, so one firm's volume is invisible to another")
    void eachFirmCountsSeparately() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String myClient = createClient(mine.adminToken(), "Mine", "mine@client.test");
        String theirClient = createClient(theirs.adminToken(), "Theirs", "theirs@client.test");

        createCase(mine.adminToken(), myClient, "My first");
        createCase(mine.adminToken(), myClient, "My second");
        createCase(mine.adminToken(), myClient, "My third");
        createCase(theirs.adminToken(), theirClient, "Their first");

        int year = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear();
        assertThat(numbersFor(mine.id()))
                .containsExactly("CASE-%d-000001".formatted(year),
                        "CASE-%d-000002".formatted(year),
                        "CASE-%d-000003".formatted(year));
        assertThat(numbersFor(theirs.id()))
                .containsExactly("CASE-%d-000001".formatted(year));
    }

    @Test
    @DisplayName("the unique index, not the service, is the last word on duplicates")
    void theDatabaseRefusesADuplicateNumberWithinAFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");
        String existingNumber = jdbcTemplate.queryForObject(
                "SELECT case_number FROM casework.cases WHERE id = ?::uuid", String.class, caseId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO casework.cases
                    (id, version, created_at, updated_at, organization_id, case_number, title,
                     client_id, status, opened_at)
                VALUES (gen_random_uuid(), 0, now(), now(), ?::uuid, ?, 'Forged', ?::uuid, 'OPEN', now())
                """, firm.id(), existingNumber, clientId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private List<String> numbersFor(String organizationId) {
        return jdbcTemplate.queryForList("""
                SELECT case_number FROM casework.cases
                 WHERE organization_id = ?::uuid ORDER BY case_number
                """, String.class, organizationId);
    }

    private static void signInOnThisThread(UUID userId, UUID organizationId) {
        AuthenticatedUser caller =
                new AuthenticatedUser(userId, organizationId, "asha@sharma-legal.test", Role.FIRM_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority(Role.FIRM_ADMIN.authority()))));
    }

    private static String value(Future<String> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("A concurrent case creation failed", e);
        }
    }
}
