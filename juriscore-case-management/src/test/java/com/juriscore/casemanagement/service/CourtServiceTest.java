package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CourtRequest;
import com.juriscore.casemanagement.domain.Court;
import com.juriscore.casemanagement.domain.CourtType;
import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.casemanagement.event.CourtCreatedEvent;
import com.juriscore.casemanagement.repository.CourtRepository;
import com.juriscore.casemanagement.repository.HearingRepository;
import com.juriscore.casemanagement.support.CallerContext;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourtServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID OTHER_FIRM = UUID.randomUUID();
    private static final UUID COURT_ID = UUID.randomUUID();

    @Mock
    private CourtRepository courtRepository;
    @Mock
    private HearingRepository hearingRepository;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private CourtService courtService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(UUID.randomUUID(), FIRM, Role.FIRM_ADMIN);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static CourtRequest request(String name, Long version) {
        return new CourtRequest(name, CourtType.DISTRICT, "Fort", null, "Mumbai", "Maharashtra",
                "India", "Asia/Kolkata", version);
    }

    private static Court existing() {
        Court court = new Court();
        court.setOrganizationId(FIRM);
        court.setName("City Civil Court");
        court.setCourtType(CourtType.DISTRICT);
        court.setActive(true);
        return court;
    }

    @Test
    void createsACourtScopedToTheCallersFirm() {
        when(courtRepository.save(any(Court.class))).thenAnswer(call -> call.getArgument(0));

        Court created = courtService.create(FIRM, request("  City Civil Court  ", null));

        assertThat(created.getOrganizationId())
                .as("the tenant comes from the caller, never from the request body")
                .isEqualTo(FIRM);
        assertThat(created.getName()).isEqualTo("City Civil Court");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void refusesASecondCourtWithTheSameName() {
        when(courtRepository.existsByOrganizationIdAndNameIgnoreCaseAndActiveTrue(FIRM, "City Civil Court"))
                .thenReturn(true);

        assertThatThrownBy(() -> courtService.create(FIRM, request("City Civil Court", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

        verify(courtRepository, never()).save(any());
    }

    @Test
    void publishesCourtCreated() {
        when(courtRepository.save(any(Court.class))).thenAnswer(call -> call.getArgument(0));

        courtService.create(FIRM, request("City Civil Court", null));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(CourtCreatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("court.created");
        assertThat(published.getValue().organizationId()).isEqualTo(FIRM);
    }

    @Test
    @DisplayName("another firm's court is not found, not forbidden")
    void refusesToReadAcrossTenants() {
        when(courtRepository.findByIdAndOrganizationId(COURT_ID, OTHER_FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> courtService.getScoped(COURT_ID, OTHER_FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("the guard still fires if a repository ever returns a foreign row")
    void guardsAgainstAQueryThatForgotTheTenantPredicate() {
        Court foreign = existing();
        foreign.setOrganizationId(OTHER_FIRM);
        when(courtRepository.findByIdAndOrganizationId(COURT_ID, FIRM)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> courtService.getScoped(COURT_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void aRetiredCourtCannotBeChosenForANewHearing() {
        when(courtRepository.findByIdAndOrganizationIdAndActiveTrue(COURT_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> courtService.requireSelectable(COURT_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("retirement flags the row rather than removing it")
    void retirementIsDeactivation() {
        Court court = existing();
        when(courtRepository.findByIdAndOrganizationIdAndActiveTrue(COURT_ID, FIRM))
                .thenReturn(Optional.of(court));
        when(hearingRepository.existsByOrganizationIdAndCourtIdAndStatus(
                FIRM, COURT_ID, HearingStatus.SCHEDULED)).thenReturn(false);

        Court retired = courtService.retire(COURT_ID, FIRM);

        assertThat(retired.isActive()).isFalse();
        verify(courtRepository, never()).delete(any());
        verify(courtRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("a court with listings ahead of it cannot be retired out from under them")
    void refusesToRetireACourtWithScheduledHearings() {
        when(courtRepository.findByIdAndOrganizationIdAndActiveTrue(COURT_ID, FIRM))
                .thenReturn(Optional.of(existing()));
        when(hearingRepository.existsByOrganizationIdAndCourtIdAndStatus(
                FIRM, COURT_ID, HearingStatus.SCHEDULED)).thenReturn(true);

        assertThatThrownBy(() -> courtService.retire(COURT_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("a stale version is a 409, not a silent overwrite")
    void updateRefusesAStaleVersion() {
        Court court = existing();
        court.setVersion(4L);
        when(courtRepository.findByIdAndOrganizationIdAndActiveTrue(COURT_ID, FIRM))
                .thenReturn(Optional.of(court));

        assertThatThrownBy(() -> courtService.update(COURT_ID, FIRM, request("Renamed", 3L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(court.getName())
                .as("the losing writer must not have applied any of its changes")
                .isEqualTo("City Civil Court");
    }

    @Test
    void updateAppliesChangesWhenTheVersionMatches() {
        Court court = existing();
        court.setVersion(4L);
        when(courtRepository.findByIdAndOrganizationIdAndActiveTrue(COURT_ID, FIRM))
                .thenReturn(Optional.of(court));

        Court updated = courtService.update(COURT_ID, FIRM, request("City Sessions Court", 4L));

        assertThat(updated.getName()).isEqualTo("City Sessions Court");
        assertThat(updated.getCity()).isEqualTo("Mumbai");
    }

    @Test
    @DisplayName("keeping your own name on an edit is not a duplicate")
    void updateAllowsACourtToKeepItsOwnName() {
        Court court = existing();
        court.setVersion(0L);
        when(courtRepository.findByIdAndOrganizationIdAndActiveTrue(COURT_ID, FIRM))
                .thenReturn(Optional.of(court));

        Court updated = courtService.update(COURT_ID, FIRM, request("city civil court", 0L));

        assertThat(updated.getName()).isEqualTo("city civil court");
        verify(courtRepository, never())
                .existsByOrganizationIdAndNameIgnoreCaseAndActiveTrue(any(), any());
    }
}
