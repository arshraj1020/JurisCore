package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CreateHearingRequest;
import com.juriscore.casemanagement.api.dto.UpdateHearingRequest;
import com.juriscore.casemanagement.domain.Court;
import com.juriscore.casemanagement.domain.CourtType;
import com.juriscore.casemanagement.domain.Hearing;
import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.casemanagement.domain.HearingType;
import com.juriscore.casemanagement.event.HearingScheduledEvent;
import com.juriscore.casemanagement.event.HearingStatusChangedEvent;
import com.juriscore.casemanagement.repository.HearingRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HearingServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID COURT_ID = UUID.randomUUID();
    private static final UUID HEARING_ID = UUID.randomUUID();
    private static final Instant LISTED = Instant.now().plus(7, ChronoUnit.DAYS);

    @Mock
    private HearingRepository hearingRepository;
    @Mock
    private CourtService courtService;
    @Mock
    private CaseTimelineRecorder recorder;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private HearingService hearingService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(ACTOR, FIRM, Role.LAWYER);
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

    private static Court court() {
        Court court = new Court();
        court.setOrganizationId(FIRM);
        court.setName("City Civil Court");
        court.setCourtType(CourtType.DISTRICT);
        court.setActive(true);
        return court;
    }

    private static Hearing hearing() {
        Hearing hearing = new Hearing();
        hearing.setOrganizationId(FIRM);
        hearing.setCaseId(CASE_ID);
        hearing.setCourtId(COURT_ID);
        hearing.setHearingType(HearingType.MENTION);
        hearing.setStatus(HearingStatus.SCHEDULED);
        hearing.setScheduledAt(LISTED);
        return hearing;
    }

    private static CreateHearingRequest creation() {
        return new CreateHearingRequest(CASE_ID, COURT_ID, HearingType.MENTION, LISTED, 30,
                "Justice Rao", "Court 4", "First listing");
    }

    @Test
    void listsAMatterBeforeACourtInTheScheduledState() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(courtService.requireSelectable(COURT_ID, FIRM)).thenReturn(court());
        when(hearingRepository.save(any(Hearing.class))).thenAnswer(call -> call.getArgument(0));

        Hearing created = hearingService.schedule(FIRM, creation());

        assertThat(created.getOrganizationId()).isEqualTo(FIRM);
        assertThat(created.getStatus()).isEqualTo(HearingStatus.SCHEDULED);
        assertThat(created.getScheduledAt()).isEqualTo(LISTED);
        assertThat(created.getDurationMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("scheduling writes a HEARING_SCHEDULED entry on the matter's timeline")
    void schedulingReachesTheCaseTimeline() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(courtService.requireSelectable(COURT_ID, FIRM)).thenReturn(court());
        when(hearingRepository.save(any(Hearing.class))).thenAnswer(call -> call.getArgument(0));

        hearingService.schedule(FIRM, creation());

        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.HEARING_SCHEDULED), any());
    }

    @Test
    void publishesHearingScheduled() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(courtService.requireSelectable(COURT_ID, FIRM)).thenReturn(court());
        when(hearingRepository.save(any(Hearing.class))).thenAnswer(call -> call.getArgument(0));

        hearingService.schedule(FIRM, creation());

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(HearingScheduledEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("hearing.scheduled");
    }

    @Test
    @DisplayName("another firm's matter stops the request before anything is written")
    void refusesToListAgainstAForeignCase() {
        when(recorder.requireCase(CASE_ID, FIRM))
                .thenThrow(new ApiException(ErrorCode.CASE_NOT_FOUND));

        assertThatThrownBy(() -> hearingService.schedule(FIRM, creation()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CASE_NOT_FOUND);

        verify(hearingRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("a retired or foreign court stops it too")
    void refusesToListBeforeAnUnusableCourt() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(courtService.requireSelectable(COURT_ID, FIRM))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        assertThatThrownBy(() -> hearingService.schedule(FIRM, creation()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(hearingRepository, never()).save(any());
    }

    @Test
    void changingStatusMovesTheHearingAndRecordsIt() {
        Hearing hearing = hearing();
        when(hearingRepository.findByIdAndOrganizationId(HEARING_ID, FIRM))
                .thenReturn(Optional.of(hearing));
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());

        hearingService.changeStatus(HEARING_ID, FIRM, HearingStatus.COMPLETED, "Judgment reserved");

        assertThat(hearing.getStatus()).isEqualTo(HearingStatus.COMPLETED);
        assertThat(hearing.getOutcome()).isEqualTo("Judgment reserved");
        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.HEARING_COMPLETED), any());

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(HearingStatusChangedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("hearing.status_changed");
    }

    @Test
    @DisplayName("each outcome writes its own kind of timeline entry")
    void adjournmentAndCancellationAreRecordedDistinctly() {
        Hearing adjourned = hearing();
        when(hearingRepository.findByIdAndOrganizationId(HEARING_ID, FIRM))
                .thenReturn(Optional.of(adjourned));
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());

        hearingService.changeStatus(HEARING_ID, FIRM, HearingStatus.ADJOURNED, null);
        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.HEARING_ADJOURNED), any());

        hearingService.changeStatus(HEARING_ID, FIRM, HearingStatus.CANCELLED, null);
        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.HEARING_CANCELLED), any());
    }

    @Test
    @DisplayName("an illegal transition records nothing and notifies nobody")
    void anIllegalTransitionLeavesNoTrace() {
        Hearing hearing = hearing();
        hearing.setStatus(HearingStatus.COMPLETED);
        when(hearingRepository.findByIdAndOrganizationId(HEARING_ID, FIRM))
                .thenReturn(Optional.of(hearing));

        assertThatThrownBy(() -> hearingService.changeStatus(
                HEARING_ID, FIRM, HearingStatus.SCHEDULED, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        verify(recorder, never()).append(any(), any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void aForeignHearingIsNotFound() {
        when(hearingRepository.findByIdAndOrganizationId(HEARING_ID, FIRM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> hearingService.getScoped(HEARING_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.HEARING_NOT_FOUND);
    }

    @Test
    @DisplayName("the guard still fires if a repository ever returns a foreign row")
    void guardsAgainstAQueryThatForgotTheTenantPredicate() {
        Hearing foreign = hearing();
        foreign.setOrganizationId(UUID.randomUUID());
        when(hearingRepository.findByIdAndOrganizationId(HEARING_ID, FIRM))
                .thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> hearingService.getScoped(HEARING_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.HEARING_NOT_FOUND);
    }

    @Test
    void updateRefusesAStaleVersion() {
        Hearing hearing = hearing();
        hearing.setVersion(4L);
        when(hearingRepository.findByIdAndOrganizationId(HEARING_ID, FIRM))
                .thenReturn(Optional.of(hearing));

        UpdateHearingRequest stale = new UpdateHearingRequest(COURT_ID, HearingType.EVIDENCE,
                LISTED, 60, null, null, null, null, 3L);

        assertThatThrownBy(() -> hearingService.update(HEARING_ID, FIRM, stale))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(hearing.getHearingType())
                .as("the losing writer must not have applied any of its changes")
                .isEqualTo(HearingType.MENTION);
    }

    @Test
    @DisplayName("an edit cannot move a hearing through its lifecycle")
    void updateNeverTouchesStatus() {
        Hearing hearing = hearing();
        hearing.setStatus(HearingStatus.ADJOURNED);
        hearing.setVersion(0L);
        when(hearingRepository.findByIdAndOrganizationId(HEARING_ID, FIRM))
                .thenReturn(Optional.of(hearing));
        when(courtService.requireSelectable(COURT_ID, FIRM)).thenReturn(court());

        hearingService.update(HEARING_ID, FIRM, new UpdateHearingRequest(COURT_ID,
                HearingType.EVIDENCE, LISTED, 60, "Justice Rao", "Court 4", "Evidence", null, 0L));

        assertThat(hearing.getStatus()).isEqualTo(HearingStatus.ADJOURNED);
        verify(recorder, never()).append(any(), any(), any());
    }

    @Test
    @DisplayName("a half-open date range is a caller mistake, not an unbounded scan")
    void refusesAnIncompleteDateRange() {
        var page = org.springframework.data.domain.PageRequest.of(0, 20);

        assertThatThrownBy(() -> hearingService.list(FIRM, null, null, null, LISTED, null, page))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);

        assertThatThrownBy(() -> hearingService.list(FIRM, null, null, null,
                LISTED, LISTED.minusSeconds(1), page))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void listChoosesTheQueryThatMatchesTheFilters() {
        var page = org.springframework.data.domain.PageRequest.of(0, 20);

        hearingService.list(FIRM, null, null, null, null, null, page);
        verify(hearingRepository).findByOrganizationId(FIRM, page);

        hearingService.list(FIRM, CASE_ID, null, null, null, null, page);
        verify(hearingRepository).findByOrganizationIdAndCaseId(FIRM, CASE_ID, page);

        hearingService.list(FIRM, null, COURT_ID, null, null, null, page);
        verify(hearingRepository).findByOrganizationIdAndCourtId(FIRM, COURT_ID, page);

        hearingService.list(FIRM, null, null, HearingStatus.SCHEDULED, null, null, page);
        verify(hearingRepository).findByOrganizationIdAndStatus(FIRM, HearingStatus.SCHEDULED, page);

        hearingService.list(FIRM, CASE_ID, null, HearingStatus.SCHEDULED, null, null, page);
        verify(hearingRepository).findByOrganizationIdAndCaseIdAndStatus(
                FIRM, CASE_ID, HearingStatus.SCHEDULED, page);

        Instant to = LISTED.plus(1, ChronoUnit.DAYS);
        hearingService.list(FIRM, null, null, null, LISTED, to, page);
        verify(hearingRepository).findScheduledBetween(FIRM, LISTED, to, page);
    }
}
