package com.juriscore.casework.service;

import com.juriscore.casework.api.dto.CreateClientRequest;
import com.juriscore.casework.api.dto.UpdateClientRequest;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.ClientType;
import com.juriscore.casework.event.ClientCreatedEvent;
import com.juriscore.casework.repository.ClientRepository;
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
class ClientServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID OTHER_FIRM = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private ClientService clientService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(UUID.randomUUID(), FIRM, Role.FIRM_ADMIN);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static CreateClientRequest creation(String name, String email) {
        return new CreateClientRequest(name, ClientType.INDIVIDUAL, email, "+91 22 5550 1234",
                "12 Marine Drive", null, "Mumbai", "Maharashtra", "India", "400020", "Referred by counsel");
    }

    private static Client existing() {
        Client client = new Client();
        client.setOrganizationId(FIRM);
        client.setDisplayName("Asha Menon");
        client.setClientType(ClientType.INDIVIDUAL);
        client.setEmail("asha@menon.test");
        return client;
    }

    @Test
    void createsAClientScopedToTheCallersFirm() {
        when(clientRepository.save(any(Client.class))).thenAnswer(call -> call.getArgument(0));

        Client created = clientService.create(FIRM, creation("Asha Menon", "Asha@Menon.test"));

        assertThat(created.getOrganizationId())
                .as("the tenant comes from the caller, never from the request body")
                .isEqualTo(FIRM);
        assertThat(created.getDisplayName()).isEqualTo("Asha Menon");
        assertThat(created.getClientType()).isEqualTo(ClientType.INDIVIDUAL);
        assertThat(created.getCity()).isEqualTo("Mumbai");
        assertThat(created.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("stores the address in lower case, so the unique index and the lookups agree")
    void normalisesTheEmailAddress() {
        when(clientRepository.save(any(Client.class))).thenAnswer(call -> call.getArgument(0));

        Client created = clientService.create(FIRM, creation("Asha Menon", "  Asha@Menon.TEST  "));

        assertThat(created.getEmail()).isEqualTo("asha@menon.test");
    }

    @Test
    void treatsABlankEmailAsNoEmail() {
        when(clientRepository.save(any(Client.class))).thenAnswer(call -> call.getArgument(0));

        Client created = clientService.create(FIRM, creation("Trust with no contact", "   "));

        assertThat(created.getEmail())
                .as("an empty string would collide with the next empty string in the unique index")
                .isNull();
        verify(clientRepository, never())
                .existsByOrganizationIdAndEmailIgnoreCaseAndDeletedAtIsNull(any(), any());
    }

    @Test
    void refusesASecondLiveClientWithTheSameAddress() {
        when(clientRepository.existsByOrganizationIdAndEmailIgnoreCaseAndDeletedAtIsNull(
                FIRM, "asha@menon.test")).thenReturn(true);

        assertThatThrownBy(() -> clientService.create(FIRM, creation("Asha Menon", "asha@menon.test")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

        verify(clientRepository, never()).save(any());
    }

    @Test
    void publishesClientCreated() {
        when(clientRepository.save(any(Client.class))).thenAnswer(call -> call.getArgument(0));

        clientService.create(FIRM, creation("Asha Menon", "asha@menon.test"));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(ClientCreatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("client.created");
        assertThat(published.getValue().organizationId()).isEqualTo(FIRM);
    }

    @Test
    @DisplayName("a client of another firm is not found, not forbidden")
    void refusesToReadAcrossTenants() {
        when(clientRepository.findByIdAndOrganizationId(CLIENT_ID, OTHER_FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> clientService.getScoped(CLIENT_ID, OTHER_FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("the guard still fires if a repository ever returns a foreign row")
    void guardsAgainstAQueryThatForgotTheTenantPredicate() {
        Client foreign = existing();
        foreign.setOrganizationId(OTHER_FIRM);
        when(clientRepository.findByIdAndOrganizationId(CLIENT_ID, FIRM))
                .thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> clientService.getScoped(CLIENT_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    void softDeleteStampsTheRowRatherThanRemovingIt() {
        Client client = existing();
        when(clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(CLIENT_ID, FIRM))
                .thenReturn(Optional.of(client));

        Client deleted = clientService.softDelete(CLIENT_ID, FIRM);

        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.isDeleted()).isTrue();
        verify(clientRepository, never()).delete(any());
        verify(clientRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("a second delete answers not-found, because the client is no longer actionable")
    void deletingTwiceIsNotFound() {
        when(clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(CLIENT_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> clientService.softDelete(CLIENT_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    void aSoftDeletedClientCannotBeSelectedForANewCase() {
        when(clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(CLIENT_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> clientService.requireSelectable(CLIENT_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("a soft-deleted client is still readable by id, so old matters resolve")
    void softDeletedClientsRemainReadable() {
        Client client = existing();
        client.markDeleted(Instant.now());
        when(clientRepository.findByIdAndOrganizationId(CLIENT_ID, FIRM))
                .thenReturn(Optional.of(client));

        assertThat(clientService.getScoped(CLIENT_ID, FIRM).isDeleted()).isTrue();
    }

    @Test
    void updateRefusesAnAddressAlreadyUsedByAnotherLiveClient() {
        when(clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(CLIENT_ID, FIRM))
                .thenReturn(Optional.of(existing()));
        when(clientRepository.emailTakenByAnother(FIRM, "taken@firm.test", CLIENT_ID))
                .thenReturn(true);

        UpdateClientRequest request = new UpdateClientRequest("Asha Menon", ClientType.INDIVIDUAL,
                "taken@firm.test", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> clientService.update(CLIENT_ID, FIRM, request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    @DisplayName("keeping your own address on an edit is not a duplicate")
    void updateAllowsAClientToKeepItsOwnAddress() {
        Client client = existing();
        when(clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(CLIENT_ID, FIRM))
                .thenReturn(Optional.of(client));
        when(clientRepository.emailTakenByAnother(eq(FIRM), eq("asha@menon.test"), eq(CLIENT_ID)))
                .thenReturn(false);

        UpdateClientRequest request = new UpdateClientRequest("Asha Menon-Iyer", ClientType.INDIVIDUAL,
                "asha@menon.test", null, null, null, null, null, null, null, null);

        Client updated = clientService.update(CLIENT_ID, FIRM, request);

        assertThat(updated.getDisplayName()).isEqualTo("Asha Menon-Iyer");
        assertThat(updated.getEmail()).isEqualTo("asha@menon.test");
    }

    @Test
    void listWithoutASearchTermReturnsOnlyLiveClients() {
        clientService.list(FIRM, "   ", org.springframework.data.domain.PageRequest.of(0, 20));

        verify(clientRepository).findByOrganizationIdAndDeletedAtIsNull(eq(FIRM), any());
        verify(clientRepository, never()).search(any(), any(), any());
    }
}
