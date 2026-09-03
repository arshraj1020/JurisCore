package com.juriscore.casemanagement;

import com.juriscore.casemanagement.domain.ReminderStatus;
import com.juriscore.casemanagement.event.ReminderTriggeredEvent;
import com.juriscore.casemanagement.service.ReminderDispatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reminder sweep against a real database.
 *
 * <p>The sweep is driven directly rather than by the timer — {@code juriscore.reminders
 * .enabled} is false in the test profile — so these tests are about the work, not about
 * whether a scheduler ticked. That separation is the reason
 * {@code ReminderDispatchService} exists apart from {@code ReminderScheduler}.
 *
 * <p>The test that matters most is the last one. Every instance of this application runs
 * the same sweep on the same schedule, so "select the due rows, then update them" has all
 * of them selecting the same rows and publishing the same reminder several times. That
 * failure is invisible to a unit test with a mocked repository, because a mock has no row
 * locks. Here it is real transactions on real PostgreSQL.
 */
class ReminderDispatchIT extends AbstractCaseManagementIT {

    @Autowired
    private ReminderDispatchService dispatchService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Schedules a reminder and then backdates it, since the API refuses a past time. */
    private String dueReminder(Matter matter, String label) throws Exception {
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), label, null);
        String reminderId = remindOnTask(token, taskId, Instant.now().plus(2, ChronoUnit.DAYS));
        jdbcTemplate.update(
                "UPDATE case_management.reminders SET remind_at = ? WHERE id = ?::uuid",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), reminderId);
        return reminderId;
    }

    @Test
    @DisplayName("a due reminder is marked SENT and announced — and nothing more happens")
    void aDueReminderIsClaimedAndPublished() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String reminderId = dueReminder(matter, "Draft");
        events.clear();

        int published = dispatchService.dispatchDue();

        assertThat(published).isEqualTo(1);
        assertThat(statusOf(reminderId)).isEqualTo(ReminderStatus.SENT.name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT triggered_at IS NOT NULL FROM case_management.reminders WHERE id = ?::uuid",
                Boolean.class, reminderId)).isTrue();

        ReminderTriggeredEvent event = events.require(ReminderTriggeredEvent.class);
        assertThat(event.eventType()).isEqualTo("reminder.triggered");
        assertThat(event.organizationId()).hasToString(matter.firm().id());
    }

    @Test
    @DisplayName("a reminder whose time has not come is left alone")
    void futureRemindersAreNotTouched() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        String reminderId = remindOnTask(token, taskId, Instant.now().plus(2, ChronoUnit.DAYS));

        assertThat(dispatchService.dispatchDue()).isZero();
        assertThat(statusOf(reminderId)).isEqualTo(ReminderStatus.SCHEDULED.name());
    }

    @Test
    @DisplayName("a cancelled reminder never fires, however overdue it looks")
    void cancelledRemindersAreNeverClaimed() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String reminderId = dueReminder(matter, "Draft");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/reminders/" + reminderId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        assertThat(dispatchService.dispatchDue()).isZero();
        assertThat(statusOf(reminderId)).isEqualTo(ReminderStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("a second sweep does not fire the same reminder again")
    void aSweepIsIdempotentAcrossRuns() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        dueReminder(matter, "Draft");

        assertThat(dispatchService.dispatchDue()).isEqualTo(1);
        assertThat(dispatchService.dispatchDue())
                .as("the first sweep moved it out of SCHEDULED, so the claim query no longer sees it")
                .isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.reminders WHERE status = 'SENT'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void aSweepClaimsEveryDueReminderAcrossFirms() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        dueReminder(mine, "Mine one");
        dueReminder(mine, "Mine two");
        dueReminder(theirs, "Theirs");

        assertThat(dispatchService.dispatchDue())
                .as("the sweep is the platform's, not a caller's — it is the one place with no "
                        + "tenant to scope to")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the batch size bounds one sweep, so a backlog is worked through over several")
    void aSweepIsBounded() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        for (int i = 0; i < 5; i++) {
            dueReminder(matter, "Draft " + i);
        }

        assertThat(dispatchService.dispatchDue(Instant.now(), 2)).isEqualTo(2);
        assertThat(dispatchService.dispatchDue(Instant.now(), 2)).isEqualTo(2);
        assertThat(dispatchService.dispatchDue(Instant.now(), 2)).isEqualTo(1);
        assertThat(dispatchService.dispatchDue(Instant.now(), 2)).isZero();
    }

    @Test
    @DisplayName("four concurrent sweeps claim disjoint batches — no reminder fires twice")
    void concurrentSweepsNeverClaimTheSameReminder() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        int total = 12;
        for (int i = 0; i < total; i++) {
            dueReminder(matter, "Draft " + i);
        }

        int sweepers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(sweepers);
        try {
            TransactionTemplate transactions = new TransactionTemplate(transactionManager);
            List<Callable<Integer>> sweeps = IntStream.range(0, sweepers)
                    .mapToObj(i -> (Callable<Integer>) () ->
                            transactions.execute(status -> dispatchService.dispatchDue(
                                    Instant.now(), total)))
                    .toList();

            List<Future<Integer>> results = pool.invokeAll(sweeps, 60, TimeUnit.SECONDS);
            int claimed = results.stream().mapToInt(ReminderDispatchIT::value).sum();

            assertThat(claimed)
                    .as("FOR UPDATE SKIP LOCKED makes the batches disjoint by construction; "
                            + "without it every sweeper would claim all %d", total)
                    .isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.reminders WHERE status = 'SENT'", Long.class))
                .isEqualTo((long) total);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.reminders WHERE status = 'SCHEDULED'",
                Long.class))
                .as("and nothing is left behind")
                .isZero();
    }

    @Test
    @DisplayName("every claimed reminder is published exactly once")
    void eachReminderIsAnnouncedOnce() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        for (int i = 0; i < 4; i++) {
            dueReminder(matter, "Draft " + i);
        }
        events.clear();

        dispatchService.dispatchDue();

        Set<Object> reminderIds = events.all().stream()
                .filter(ReminderTriggeredEvent.class::isInstance)
                .map(e -> ((ReminderTriggeredEvent) e).getReminderId())
                .collect(Collectors.toSet());
        assertThat(reminderIds).hasSize(4);
    }

    private String statusOf(String reminderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM case_management.reminders WHERE id = ?::uuid",
                String.class, reminderId);
    }

    private static int value(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("A concurrent sweep failed", e);
        }
    }
}
