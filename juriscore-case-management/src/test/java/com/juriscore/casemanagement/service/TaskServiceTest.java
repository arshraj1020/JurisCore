package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CreateTaskRequest;
import com.juriscore.casemanagement.api.dto.UpdateTaskRequest;
import com.juriscore.casemanagement.domain.Task;
import com.juriscore.casemanagement.domain.TaskPriority;
import com.juriscore.casemanagement.domain.TaskStatus;
import com.juriscore.casemanagement.event.TaskCompletedEvent;
import com.juriscore.casemanagement.event.TaskCreatedEvent;
import com.juriscore.casemanagement.repository.TaskRepository;
import com.juriscore.casemanagement.support.CallerContext;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.LegalCase;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ASSIGNEE = UUID.randomUUID();

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private FirmMemberDirectory firmMembers;
    @Mock
    private CaseTimelineRecorder recorder;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private TaskService taskService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(ACTOR, FIRM, Role.CLERK);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static LegalCase legalCase() {
        LegalCase legalCase = new LegalCase();
        legalCase.setOrganizationId(FIRM);
        legalCase.setCaseNumber("CASE-2026-000001");
        legalCase.setTitle("Menon v. Iyer");
        legalCase.setClientId(UUID.randomUUID());
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(Instant.now());
        return legalCase;
    }

    private static Task task() {
        Task task = new Task();
        task.setOrganizationId(FIRM);
        task.setCaseId(CASE_ID);
        task.setTitle("Draft the reply");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(TaskPriority.MEDIUM);
        return task;
    }

    @Test
    void createsATaskInTheTodoState() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        Task created = taskService.create(CASE_ID, FIRM, new CreateTaskRequest(
                "  Draft the reply  ", "Two pages", TaskPriority.HIGH, ASSIGNEE, null));

        assertThat(created.getOrganizationId()).isEqualTo(FIRM);
        assertThat(created.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(created.getTitle()).isEqualTo("Draft the reply");
        assertThat(created.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(created.getAssignedToUserId()).isEqualTo(ASSIGNEE);
        verify(firmMembers).requireAssignableMember(ASSIGNEE, FIRM);
    }

    @Test
    @DisplayName("work can exist before anybody has it")
    void anUnassignedTaskIsAllowed() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        Task created = taskService.create(CASE_ID, FIRM, new CreateTaskRequest(
                "Chase the registry", null, TaskPriority.LOW, null, null));

        assertThat(created.getAssignedToUserId()).isNull();
        verify(firmMembers, never()).requireAssignableMember(any(), any());
    }

    @Test
    @DisplayName("an assignee from another firm stops the request before anything is written")
    void refusesAForeignAssignee() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        doThrow(new ApiException(ErrorCode.USER_NOT_FOUND))
                .when(firmMembers).requireAssignableMember(ASSIGNEE, FIRM);

        assertThatThrownBy(() -> taskService.create(CASE_ID, FIRM, new CreateTaskRequest(
                "Draft the reply", null, TaskPriority.MEDIUM, ASSIGNEE, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(taskRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void creationRecordsAndPublishes() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        taskService.create(CASE_ID, FIRM, new CreateTaskRequest(
                "Draft the reply", null, TaskPriority.MEDIUM, null, null));

        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.TASK_CREATED), any());
        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(TaskCreatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("task.created");
    }

    @Test
    void completingATaskStampsItRecordsItAndPublishes() {
        Task task = task();
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.of(task));
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());

        taskService.changeStatus(TASK_ID, FIRM, TaskStatus.COMPLETED);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedAt()).isNotNull();
        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.TASK_COMPLETED), any());

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(TaskCompletedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("task.completed");
    }

    @Test
    @DisplayName("cancelling reaches the timeline but is not a completion event")
    void cancellationIsRecordedButNotPublishedAsCompletion() {
        Task task = task();
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.of(task));
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());

        taskService.changeStatus(TASK_ID, FIRM, TaskStatus.CANCELLED);

        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.TASK_CANCELLED), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("a task picked up and put down again does not clutter the matter's history")
    void intermediateMovesDoNotReachTheTimeline() {
        Task task = task();
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.of(task));

        taskService.changeStatus(TASK_ID, FIRM, TaskStatus.IN_PROGRESS);
        taskService.changeStatus(TASK_ID, FIRM, TaskStatus.TODO);

        verify(recorder, never()).append(any(), any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void anIllegalTransitionLeavesNoTrace() {
        Task task = task();
        task.transitionTo(TaskStatus.COMPLETED, Instant.now());
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.changeStatus(TASK_ID, FIRM, TaskStatus.IN_PROGRESS))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        verify(recorder, never()).append(any(), any(), any());
    }

    @Test
    void removalIsSoft() {
        Task task = task();
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.of(task));

        Task removed = taskService.remove(TASK_ID, FIRM);

        assertThat(removed.isDeleted()).isTrue();
        verify(taskRepository, never()).delete(any());
        verify(taskRepository, never()).deleteById(any());
    }

    @Test
    void removingTwiceIsNotFound() {
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.remove(TASK_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TASK_NOT_FOUND);
    }

    @Test
    @DisplayName("a removed task is still readable, so a timeline entry naming it resolves")
    void removedTasksRemainReadable() {
        Task task = task();
        task.markDeleted(Instant.now());
        when(taskRepository.findByIdAndOrganizationId(TASK_ID, FIRM)).thenReturn(Optional.of(task));

        assertThat(taskService.getScoped(TASK_ID, FIRM).isDeleted()).isTrue();
    }

    @Test
    void aForeignTaskIsNotFound() {
        when(taskRepository.findByIdAndOrganizationId(TASK_ID, FIRM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.getScoped(TASK_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TASK_NOT_FOUND);
    }

    @Test
    @DisplayName("the guard still fires if a repository ever returns a foreign row")
    void guardsAgainstAQueryThatForgotTheTenantPredicate() {
        Task foreign = task();
        foreign.setOrganizationId(UUID.randomUUID());
        when(taskRepository.findByIdAndOrganizationId(TASK_ID, FIRM)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> taskService.getScoped(TASK_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TASK_NOT_FOUND);
    }

    @Test
    void updateRefusesAStaleVersion() {
        Task task = task();
        task.setVersion(4L);
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.update(TASK_ID, FIRM, new UpdateTaskRequest(
                "Renamed", null, TaskPriority.URGENT, null, null, 3L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(task.getTitle()).isEqualTo("Draft the reply");
    }

    @Test
    @DisplayName("an edit cannot complete somebody's work")
    void updateNeverTouchesStatus() {
        Task task = task();
        task.setVersion(0L);
        when(taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(TASK_ID, FIRM))
                .thenReturn(Optional.of(task));

        taskService.update(TASK_ID, FIRM, new UpdateTaskRequest(
                "Draft the rejoinder", null, TaskPriority.URGENT, null, null, 0L));

        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getTitle()).isEqualTo("Draft the rejoinder");
    }
}
