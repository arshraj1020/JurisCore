package com.juriscore.casemanagement;

import com.juriscore.casemanagement.event.ReminderScheduledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reminders as an API. What happens when one comes due is {@code ReminderDispatchIT}.
 *
 * <p>Nothing here delivers anything, and the tests are written so that stays visible: the
 * assertions are about rows and events, never about a message reaching a person.
 */
class ReminderIT extends AbstractCaseManagementIT {

    private static final Instant SOON = Instant.now().plus(2, ChronoUnit.DAYS);

    @Test
    void schedulesAReminderOnATask() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);

        String reminderId = remindOnTask(token, taskId, SOON);

        mockMvc.perform(get("/api/v1/reminders/" + reminderId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.channel").value("IN_APP"));

        assertThat(events.require(ReminderScheduledEvent.class).eventType())
                .isEqualTo("reminder.scheduled");
    }

    @Test
    void schedulesAReminderOnADeadline() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String deadlineId = createDeadline(token, matter.caseId(), "File",
                Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/deadlines/" + deadlineId + "/reminders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reminderBody(SOON)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deadlineId").value(deadlineId))
                .andExpect(jsonPath("$.data.taskId").doesNotExist());
    }

    @Test
    @DisplayName("exactly one target, enforced by the database as well as the API shape")
    void aReminderHasExactlyOneTarget() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        String reminderId = remindOnTask(token, taskId, SOON);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT (task_id IS NOT NULL)::int + (deadline_id IS NOT NULL)::int
                  FROM case_management.reminders WHERE id = ?::uuid
                """, Integer.class, reminderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a reminder in the past would fire on the very next sweep, which nobody meant")
    void refusesAReminderInThePast() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/reminders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reminderBody(Instant.now().minus(1, ChronoUnit.HOURS))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void refusesAReminderOnFinishedWork() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        moveTask(token, taskId, "COMPLETED", 200);

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/reminders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reminderBody(SOON)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    void refusesAReminderOnARemovedTask() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        mockMvc.perform(delete("/api/v1/tasks/" + taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/reminders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reminderBody(SOON)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("setting a reminder is not a way to find out which ids exist elsewhere")
    void refusesAForeignTarget() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirTask = createTask(theirs.firm().adminToken(), theirs.caseId(), "Theirs", null);

        mockMvc.perform(post("/api/v1/tasks/" + theirTask + "/reminders")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reminderBody(SOON)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.reminders", Long.class)).isZero();
    }

    @Test
    void reschedulesAReminderAndRefusesAStaleVersion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        String reminderId = remindOnTask(token, taskId, SOON);
        long stale = versionOf("reminders", reminderId);
        Instant later = SOON.plus(1, ChronoUnit.DAYS);

        mockMvc.perform(put("/api/v1/reminders/" + reminderId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(later, stale)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("EMAIL"));

        mockMvc.perform(put("/api/v1/reminders/" + reminderId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(later, stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("cancelling stops the reminder without deleting the row")
    void cancellationIsNotDeletion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        String reminderId = remindOnTask(token, taskId, SOON);

        mockMvc.perform(delete("/api/v1/reminders/" + reminderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.reminders WHERE id = ?::uuid",
                Long.class, reminderId))
                .as("'this fired' and 'somebody stopped it' have to stay distinguishable")
                .isEqualTo(1L);

        mockMvc.perform(delete("/api/v1/reminders/" + reminderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void filtersByStatusAndListsOnlyTheCallersOwn() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String myTask = createTask(mine.firm().adminToken(), mine.caseId(), "Mine", null);
        String theirTask = createTask(theirs.firm().adminToken(), theirs.caseId(), "Theirs", null);
        String mineReminder = remindOnTask(mine.firm().adminToken(), myTask, SOON);
        remindOnTask(theirs.firm().adminToken(), theirTask, SOON);

        mockMvc.perform(get("/api/v1/reminders")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(mineReminder));

        mockMvc.perform(get("/api/v1/reminders").param("status", "CANCELLED")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    @DisplayName("another firm's reminder is not found, on every verb")
    void aForeignReminderIsNotFound() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirTask = createTask(theirs.firm().adminToken(), theirs.caseId(), "Theirs", null);
        String theirReminder = remindOnTask(theirs.firm().adminToken(), theirTask, SOON);
        String token = mine.firm().adminToken();

        mockMvc.perform(get("/api/v1/reminders/" + theirReminder)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/reminders/" + theirReminder)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(SOON, 0L)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/reminders/" + theirReminder)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownReminderIsIndistinguishableFromAForeignOne() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/reminders/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private String updateBody(Instant when, long version) {
        return """
                {"remindAt":"%s","channel":"EMAIL","note":"Chase again","version":%d}
                """.formatted(when, version);
    }
}
