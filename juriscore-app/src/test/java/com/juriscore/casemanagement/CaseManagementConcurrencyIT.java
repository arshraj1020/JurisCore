package com.juriscore.casemanagement;

import com.juriscore.casemanagement.domain.Hearing;
import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.casemanagement.domain.Task;
import com.juriscore.casemanagement.repository.HearingRepository;
import com.juriscore.casemanagement.repository.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two people, one row.
 *
 * <p>Tested at both levels, because they can fail independently: over HTTP, where a
 * second writer sends the version it read and must be told the world moved — this is what
 * a frontend experiences; and in the database, with two real transactions and a genuinely
 * stale entity, which is what {@code @Version} on {@code BaseEntity} is actually for. A
 * mocked repository has no transaction to lose a race in, so only the second kind proves
 * the guarantee.
 *
 * <p>Two simultaneous hearing status changes are a special case worth its own test: the
 * lifecycle refuses the second move on its own terms, so the caller gets 409 whichever
 * mechanism fires first — and it is 409 either way, which is the point.
 */
class CaseManagementConcurrencyIT extends AbstractCaseManagementIT {

    @Autowired
    private HearingRepository hearingRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("over HTTP: the second hearing editor is told, and does not overwrite")
    void aStaleHearingWriterGetsAConflict() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String courtId = createCourt(token, "City Civil Court");
        String hearingId = scheduleHearing(token, matter.caseId(), courtId, NEXT_WEEK);

        long versionBothRead = versionOf("hearings", hearingId);

        mockMvc.perform(put("/api/v1/hearings/" + hearingId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hearingEdit(courtId, "Amended by the first lawyer", versionBothRead)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/hearings/" + hearingId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hearingEdit(courtId, "Amended by the second lawyer", versionBothRead)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/hearings/" + hearingId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.purpose").value("Amended by the first lawyer"));
    }

    @Test
    @DisplayName("over HTTP: the second task editor is told, and does not overwrite")
    void aStaleTaskWriterGetsAConflict() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        long versionBothRead = versionOf("tasks", taskId);

        mockMvc.perform(put("/api/v1/tasks/" + taskId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskEdit("Amended by the first lawyer", versionBothRead)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/tasks/" + taskId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskEdit("Amended by the second lawyer", versionBothRead)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.title").value("Amended by the first lawyer"));
    }

    @Test
    @DisplayName("in the database: a stale hearing written from a second transaction is refused")
    void aStaleHearingEntityCannotOverwriteACommittedChange() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String courtId = createCourt(token, "City Civil Court");
        UUID hearingId = UUID.fromString(scheduleHearing(token, matter.caseId(), courtId, NEXT_WEEK));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        // The first lawyer loads the listing and goes to court. The entity is detached and
        // carries the version it saw.
        Hearing stale = transactions.execute(status ->
                hearingRepository.findById(hearingId).orElseThrow());

        transactions.executeWithoutResult(status -> {
            Hearing fresh = hearingRepository.findById(hearingId).orElseThrow();
            fresh.setCourtroom("Court 9");
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            stale.setCourtroom("Court 1");
            hearingRepository.saveAndFlush(stale);
        }))
                .as("without @Version this write would land and the committed edit would vanish")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT courtroom FROM case_management.hearings WHERE id = ?", String.class, hearingId))
                .isEqualTo("Court 9");
    }

    @Test
    @DisplayName("in the database: a stale task written from a second transaction is refused")
    void aStaleTaskEntityCannotOverwriteACommittedChange() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        UUID taskId = UUID.fromString(
                createTask(matter.firm().adminToken(), matter.caseId(), "Draft", null));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Task stale = transactions.execute(status -> taskRepository.findById(taskId).orElseThrow());

        transactions.executeWithoutResult(status -> {
            Task fresh = taskRepository.findById(taskId).orElseThrow();
            fresh.setTitle("Amended by the clerk who was at their desk");
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            stale.setTitle("Amended by the clerk who was at lunch");
            taskRepository.saveAndFlush(stale);
        }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM case_management.tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("Amended by the clerk who was at their desk");
    }

    @Test
    @DisplayName("two people recording the same hearing: the second is refused, not silently applied")
    void twoSimultaneousHearingOutcomesCannotBothLand() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String courtId = createCourt(token, "City Civil Court");
        String hearingId = scheduleHearing(token, matter.caseId(), courtId, NEXT_WEEK);

        moveHearing(token, hearingId, "COMPLETED", 200);

        // The second person's move is refused by the lifecycle itself, so the answer is 409
        // whichever guard notices first — and the row keeps the first outcome.
        mockMvc.perform(patch("/api/v1/hearings/" + hearingId + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ADJOURNED\",\"outcome\":\"Second writer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        mockMvc.perform(get("/api/v1/hearings/" + hearingId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("two people completing the same task: only one completion is recorded")
    void twoSimultaneousTaskCompletionsCannotBothLand() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);

        moveTask(token, taskId, "COMPLETED", 200);
        moveTask(token, taskId, "COMPLETED", 409);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM casework.case_events
                 WHERE case_id = ?::uuid AND event_type = 'TASK_COMPLETED'
                """, Long.class, matter.caseId()))
                .as("a second completion must not append a second entry to the matter's history")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the version the API reports is the one the database is holding")
    void theReportedVersionTracksTheRow() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);

        long before = versionOf("tasks", taskId);
        mockMvc.perform(put("/api/v1/tasks/" + taskId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskEdit("Amended", before)))
                .andExpect(status().isOk());

        assertThat(versionOf("tasks", taskId)).isGreaterThan(before);
    }

    private String hearingEdit(String courtId, String purpose, long version) {
        return """
                {"courtId":"%s","hearingType":"MENTION","scheduledAt":"%s","durationMinutes":30,
                 "purpose":"%s","version":%d}
                """.formatted(courtId, NEXT_WEEK, purpose, version);
    }

    private String taskEdit(String title, long version) {
        return """
                {"title":"%s","priority":"HIGH","version":%d}
                """.formatted(title, version);
    }
}
