package com.juriscore.casework.service;

import com.juriscore.casework.api.dto.CreateCaseRequest;
import com.juriscore.casework.api.dto.UpdateCaseRequest;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.ClientType;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.event.CaseCreatedEvent;
import com.juriscore.casework.event.CaseStatusChangedEvent;
import com.juriscore.casework.repository.CaseRepository;
import com.juriscore.casework.support.CallerContext;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();

    @Mock
    private CaseRepository caseRepository;
    @Mock
    private CaseAccess caseAccess;
    @Mock
    private ClientService clientService;
    @Mock
    private CaseNumberGenerator caseNumberGenerator;
    @Mock
    private CaseTimelineService timeline;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private CaseService caseService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(ACTOR, FIRM, Role.LAWYER);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static Client client() {
        Client client = new Client();
        client.setOrganizationId(FIRM);
        client.setDisplayName("Asha Menon");
        client.setClientType(ClientType.INDIVIDUAL);
        return client;
    }

    private static LegalCase openCase() {
        LegalCase legalCase = new LegalCase();
        legalCase.setOrganizationId(FIRM);
        legalCase.setCaseNumber("CASE-2026-000001");
        legalCase.setTitle("Menon v. Iyer");
        legalCase.setClientId(CLIENT_ID);
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(Instant.now());
        return legalCase;
    }

    @Test
    void opensACaseWithASystemIssuedNumberInTheOpenState() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(client());
        when(caseNumberGenerator.nextFor(eq(FIRM), any())).thenReturn("CASE-2026-000001");
        when(caseRepository.save(any(LegalCase.class))).thenAnswer(call -> call.getArgument(0));

        LegalCase created = caseService.create(FIRM, ACTOR,
                new CreateCaseRequest("Menon v. Iyer", "Tenancy dispute", CLIENT_ID));

        assertThat(created.getOrganizationId()).isEqualTo(FIRM);
        assertThat(created.getCaseNumber()).isEqualTo("CASE-2026-000001");
        assertThat(created.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(created.getOpenedAt()).isNotNull();
        assertThat(created.getClosedAt()).isNull();
    }

    @Test
    @DisplayName("creation writes the first timeline entry in the same call")
    void creationRecordsTheOpeningOnTheTimeline() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(client());
        when(caseNumberGenerator.nextFor(eq(FIRM), any())).thenReturn("CASE-2026-000001");
        when(caseRepository.save(any(LegalCase.class))).thenAnswer(call -> call.getArgument(0));

        caseService.create(FIRM, ACTOR, new CreateCaseRequest("Menon v. Iyer", null, CLIENT_ID));

        verify(timeline).append(any(LegalCase.class), eq(CaseEventType.CASE_CREATED), eq(ACTOR), any());
    }

    @Test
    void publishesCaseCreated() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(client());
        when(caseNumberGenerator.nextFor(eq(FIRM), any())).thenReturn("CASE-2026-000001");
        when(caseRepository.save(any(LegalCase.class))).thenAnswer(call -> call.getArgument(0));

        caseService.create(FIRM, ACTOR, new CreateCaseRequest("Menon v. Iyer", null, CLIENT_ID));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(CaseCreatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("case.created");
    }

    @Test
    @DisplayName("an unusable client stops creation before a number is burned")
    void refusesToOpenACaseForAnUnusableClient() {
        when(clientService.requireSelectable(CLIENT_ID, FIRM))
                .thenThrow(new ApiException(ErrorCode.CLIENT_NOT_FOUND));

        assertThatThrownBy(() -> caseService.create(FIRM, ACTOR,
                new CreateCaseRequest("Menon v. Iyer", null, CLIENT_ID)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);

        verify(caseNumberGenerator, never()).nextFor(any(), any());
        verify(caseRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void changingStatusMovesTheCaseAndRecordsIt() {
        LegalCase legalCase = openCase();
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase);

        caseService.changeStatus(CASE_ID, FIRM, ACTOR, CaseStatus.IN_PROGRESS);

        assertThat(legalCase.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        verify(timeline).append(eq(legalCase), eq(CaseEventType.CASE_STATUS_CHANGED), eq(ACTOR), any());

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(CaseStatusChangedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("case.status_changed");
    }

    @Test
    @DisplayName("an illegal transition records nothing and notifies nobody")
    void anIllegalTransitionLeavesNoTrace() {
        LegalCase legalCase = openCase();
        legalCase.setStatus(CaseStatus.CLOSED);
        legalCase.setClosedAt(Instant.now());
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase);

        assertThatThrownBy(() -> caseService.changeStatus(CASE_ID, FIRM, ACTOR, CaseStatus.IN_PROGRESS))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        verify(timeline, never()).append(any(), any(), any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void closingStampsTheClosingTime() {
        LegalCase legalCase = openCase();
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase);

        caseService.changeStatus(CASE_ID, FIRM, ACTOR, CaseStatus.CLOSED);

        assertThat(legalCase.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("a stale version is a 409, not a silent overwrite")
    void updateRefusesAStaleVersion() {
        LegalCase legalCase = openCase();
        legalCase.setVersion(4L);
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase);

        UpdateCaseRequest stale = new UpdateCaseRequest("Renamed", null, CLIENT_ID, 3L);

        assertThatThrownBy(() -> caseService.update(CASE_ID, FIRM, stale))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(legalCase.getTitle())
                .as("the losing writer must not have applied any of its changes")
                .isEqualTo("Menon v. Iyer");
    }

    @Test
    void updateAppliesChangesWhenTheVersionMatches() {
        LegalCase legalCase = openCase();
        legalCase.setVersion(4L);
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase);
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(client());

        caseService.update(CASE_ID, FIRM, new UpdateCaseRequest("Menon v. Iyer (amended)",
                "Amended particulars", CLIENT_ID, 4L));

        assertThat(legalCase.getTitle()).isEqualTo("Menon v. Iyer (amended)");
        assertThat(legalCase.getDescription()).isEqualTo("Amended particulars");
    }

    @Test
    @DisplayName("an edit cannot move a case through its lifecycle")
    void updateNeverTouchesStatus() {
        LegalCase legalCase = openCase();
        legalCase.setStatus(CaseStatus.ON_HOLD);
        legalCase.setVersion(0L);
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase);
        when(clientService.requireSelectable(CLIENT_ID, FIRM)).thenReturn(client());

        caseService.update(CASE_ID, FIRM, new UpdateCaseRequest("Renamed", null, CLIENT_ID, 0L));

        assertThat(legalCase.getStatus()).isEqualTo(CaseStatus.ON_HOLD);
        verify(timeline, never()).append(any(), eq(CaseEventType.CASE_STATUS_CHANGED), any(), any());
    }

    @Test
    void listChoosesTheQueryThatMatchesTheFilters() {
        var page = org.springframework.data.domain.PageRequest.of(0, 20);

        caseService.list(FIRM, null, null, null, page);
        verify(caseRepository).findByOrganizationId(FIRM, page);

        caseService.list(FIRM, CaseStatus.OPEN, null, null, page);
        verify(caseRepository).findByOrganizationIdAndStatus(FIRM, CaseStatus.OPEN, page);

        caseService.list(FIRM, null, CLIENT_ID, null, page);
        verify(caseRepository).findByOrganizationIdAndClientId(FIRM, CLIENT_ID, page);

        caseService.list(FIRM, CaseStatus.OPEN, CLIENT_ID, null, page);
        verify(caseRepository).findByOrganizationIdAndStatusAndClientId(FIRM, CaseStatus.OPEN, CLIENT_ID, page);

        caseService.list(FIRM, null, null, " tenancy ", page);
        verify(caseRepository).search(FIRM, "tenancy", page);
    }
}
