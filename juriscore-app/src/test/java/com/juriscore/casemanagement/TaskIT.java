package com.juriscore.casemanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.juriscore.casemanagement.event.TaskCompletedEvent;
import com.juriscore.casemanagement.event.TaskCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Work on matters: creating it, assigning it, moving it, and removing it. */
class TaskIT extends AbstractCaseManagementIT {

    @Test
    void createsATaskInTheTodoState() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");

        String taskId = createTask(matter.firm().adminToken(), matter.caseId(), "Draft the reply", null);

        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Draft the reply"))
                .andExpect(jsonPath("$.data.status").value("TODO"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.caseId").value(matter.caseId()));

        assertThat(events.require(TaskCreatedEvent.class).eventType()).isEqualTo("task.created");
        assertThat(timelineTypes(matter)).containsExactly("TASK_CREATED", "CASE_CREATED");
    }

    @Test
    void assignsWorkToAnActiveMemberOfTheFirm() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(matter.firm(), "ravi@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");

        String taskId = createTask(matter.firm().adminToken(), matter.caseId(), "Draft", ravi);

        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(jsonPath("$.data.assignedToUserId").value(ravi.toString()));
    }

    @Test
    @DisplayName("a clerk can be given work — this is not the counsel rule")
    void aClerkCanBeAssignedATask() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(matter.firm(), "clerk@sharma-legal.test", "CLERK");

        createTask(matter.firm().adminToken(), matter.caseId(), "Chase the registry",
                userIdOf("clerk@sharma-legal.test"));
    }

    @Test
    @DisplayName("somebody at another firm can never be given work, and answers not-found")
    void refusesAnAssigneeFromAnotherFirm() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        inviteAndActivate(theirs, "outsider@kulkarni-legal.test", "LAWYER");
        UUID outsider = userIdOf("outsider@kulkarni-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + mine.caseId() + "/tasks")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Draft", outsider)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        assertThat(taskCount()).isZero();
    }

    @Test
    void refusesASuspendedAssignee() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(matter.firm(), "ravi@sharma-legal.test", "LAWYER");
        UUID ravi = userIdOf("ravi@sharma-legal.test");
        suspend(matter.firm(), "ravi@sharma-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/tasks")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Draft", ravi)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("a client of the firm is not staff and cannot be given work")
    void refusesAClientRoleAssignee() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        inviteAndActivate(matter.firm(), "portal@sharma-legal.test", "CLIENT");

        mockMvc.perform(post("/api/v1/cases/" + matter.caseId() + "/tasks")
                        .header("Authorization", bearer(matter.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Draft", userIdOf("portal@sharma-legal.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void refusesATaskOnAnotherFirmsMatter() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(post("/api/v1/cases/" + theirs.caseId() + "/tasks")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Injected", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------------ lifecycle

    @Test
    void completingATaskStampsItAndReachesTheTimeline() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String taskId = createTask(matter.firm().adminToken(), matter.caseId(), "Draft", null);
        events.clear();

        moveTask(matter.firm().adminToken(), taskId, "COMPLETED", 200);

        mockMvc.perform(get("/api/v1/tasks/" + taskId)
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").exists());

        assertThat(events.require(TaskCompletedEvent.class).eventType()).isEqualTo("task.completed");
        assertThat(timelineTypes(matter)).containsExactly("TASK_COMPLETED", "TASK_CREATED", "CASE_CREATED");
    }

    @Test
    @DisplayName("work picked up and put down again does not clutter the matter's history")
    void intermediateMovesDoNotReachTheTimeline() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String taskId = createTask(matter.firm().adminToken(), matter.caseId(), "Draft", null);

        moveTask(matter.firm().adminToken(), taskId, "IN_PROGRESS", 200);
        moveTask(matter.firm().adminToken(), taskId, "TODO", 200);
        moveTask(matter.firm().adminToken(), taskId, "IN_PROGRESS", 200);

        assertThat(timelineTypes(matter)).containsExactly("TASK_CREATED", "CASE_CREATED");
    }

    @Test
    void aCompletedTaskIsTerminal() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String taskId = createTask(matter.firm().adminToken(), matter.caseId(), "Draft", null);
        moveTask(matter.firm().adminToken(), taskId, "COMPLETED", 200);

        moveTask(matter.firm().adminToken(), taskId, "IN_PROGRESS", 409);
        moveTask(matter.firm().adminToken(), taskId, "CANCELLED", 409);

        assertThat(timelineTypes(matter))
                .containsExactly("TASK_COMPLETED", "TASK_CREATED", "CASE_CREATED");
    }

    @Test
    void cancellingReachesTheTimelineButIsNotACompletion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String taskId = createTask(matter.firm().adminToken(), matter.caseId(), "Draft", null);
        events.clear();

        moveTask(matter.firm().adminToken(), taskId, "CANCELLED", 200);

        assertThat(events.latest(TaskCompletedEvent.class)).isEmpty();
        assertThat(timelineTypes(matter))
                .containsExactly("TASK_CANCELLED", "TASK_CREATED", "CASE_CREATED");
    }

    // -------------------------------------------------------------------------- editing

    @Test
    void editsATaskAndRefusesAStaleVersion() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);
        long stale = versionOf("tasks", taskId);

        mockMvc.perform(put("/api/v1/tasks/" + taskId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("Draft the rejoinder", stale)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Draft the rejoinder"));

        mockMvc.perform(put("/api/v1/tasks/" + taskId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("Losing edit", stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.title").value("Draft the rejoinder"));
    }

    @Test
    @DisplayName("an edit cannot complete somebody's work")
    void putCannotChangeStatus() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);

        mockMvc.perform(put("/api/v1/tasks/" + taskId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Draft","priority":"LOW","version":%d,"status":"COMPLETED"}
                                """.formatted(versionOf("tasks", taskId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TODO"));
    }

    // -------------------------------------------------------------------------- removal

    @Test
    void removalIsSoftAndHidesTheTaskFromTheList() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String kept = createTask(token, matter.caseId(), "Kept", null);
        String removed = createTask(token, matter.caseId(), "Removed", null);

        mockMvc.perform(delete("/api/v1/tasks/" + removed).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedAt").exists());

        mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/tasks")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(kept));

        Long remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.tasks WHERE id = ?::uuid", Long.class, removed);
        assertThat(remaining).as("the row stays so the matter's timeline keeps resolving")
                .isEqualTo(1L);

        mockMvc.perform(get("/api/v1/tasks/" + removed).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void removingTwiceIsNotFound() throws Exception {
        Matter matter = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        String token = matter.firm().adminToken();
        String taskId = createTask(token, matter.caseId(), "Draft", null);

        mockMvc.perform(delete("/api/v1/tasks/" + taskId).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/tasks/" + taskId).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another firm's task is not found, on every verb")
    void aForeignTaskIsNotFound() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirTask = createTask(theirs.firm().adminToken(), theirs.caseId(), "Theirs", null);
        String token = mine.firm().adminToken();

        mockMvc.perform(get("/api/v1/tasks/" + theirTask).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TASK_NOT_FOUND"));

        moveTask(token, theirTask, "COMPLETED", 404);

        mockMvc.perform(put("/api/v1/tasks/" + theirTask).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("Hijacked", 0L)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/tasks/" + theirTask).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsOnlyTheCallersOwnTasks() throws Exception {
        Matter mine = openMatter("Sharma & Associates", "asha@sharma-legal.test");
        Matter theirs = openMatter("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        createTask(mine.firm().adminToken(), mine.caseId(), "Mine", null);
        createTask(theirs.firm().adminToken(), theirs.caseId(), "Theirs", null);

        mockMvc.perform(get("/api/v1/cases/" + mine.caseId() + "/tasks")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Mine"));
    }

    // ------------------------------------------------------------------------ helpers

    private String editBody(String title, long version) {
        return """
                {"title":"%s","description":"Amended","priority":"URGENT","version":%d}
                """.formatted(title, version);
    }

    private long taskCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM case_management.tasks", Long.class);
        return count == null ? 0 : count;
    }

    private List<String> timelineTypes(Matter matter) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cases/" + matter.caseId() + "/timeline")
                        .param("size", "50")
                        .header("Authorization", bearer(matter.firm().adminToken())))
                .andExpect(status().isOk())
                .andReturn();
        List<String> types = new ArrayList<>();
        for (JsonNode item : json(result).path("data").path("items")) {
            types.add(item.path("eventType").asText());
        }
        return types;
    }
}
