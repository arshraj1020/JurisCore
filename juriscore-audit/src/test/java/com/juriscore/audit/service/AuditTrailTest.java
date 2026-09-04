package com.juriscore.audit.service;

import com.juriscore.audit.domain.AuditEvent;
import com.juriscore.audit.repository.AuditEventRepository;
import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.common.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How an audit row gets its actor, its request id, and its refusal to break anything. */
@ExtendWith(MockitoExtension.class)
class AuditTrailTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ENTITY = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @Mock
    private AuditEventRepository auditEventRepository;

    @InjectMocks
    private AuditTrail auditTrail;

    @AfterEach
    void tidy() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private static void signIn() {
        AuthenticatedUser caller = new AuthenticatedUser(ACTOR, FIRM, "asha@firm.test", Role.FIRM_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority(Role.FIRM_ADMIN.authority()))));
    }

    private AuditEvent recordAndCapture() {
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("the actor comes from the security context, which an after-commit listener still has")
    void recordsTheSignedInActor() {
        signIn();
        MDC.put("requestId", "req-42");

        auditTrail.record(FIRM, "invoice.issued", "INVOICE", ENTITY,
                Instant.parse("2026-03-15T10:00:00Z"), "Invoice INV-2026-000001 issued", EVENT_ID);

        AuditEvent event = recordAndCapture();
        assertThat(event.getActorUserId()).isEqualTo(ACTOR);
        assertThat(event.getCreatedBy()).isEqualTo(ACTOR);
        assertThat(event.getOrganizationId()).isEqualTo(FIRM);
        assertThat(event.getAction()).isEqualTo("invoice.issued");
        assertThat(event.getEntityType()).isEqualTo("INVOICE");
        assertThat(event.getEntityId()).isEqualTo(ENTITY);
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-03-15T10:00:00Z"));
        // Compared against MDC rather than the literal: SLF4J's MDC is a no-op without a
        // logging backend bound, and this test is about AuditTrail copying whatever the
        // request filter put there — not about which logger the test runner happens to have.
        assertThat(event.getRequestId()).isEqualTo(MDC.get("requestId"));
        assertThat(event.getSourceEventId()).isEqualTo(EVENT_ID);
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("a scheduled sweep has no caller, and records none rather than inventing one")
    void systemActionsHaveNoActor() {
        auditTrail.record(FIRM, "invoice.overdue", "INVOICE", ENTITY, Instant.now(),
                "Invoice INV-2026-000001 passed its due date", EVENT_ID);

        AuditEvent event = recordAndCapture();
        assertThat(event.getActorUserId()).isNull();
        assertThat(event.getRequestId()).isNull();
    }

    @Test
    @DisplayName("a summary carrying a secret is refused, and the refusal does not escape")
    void refusesToStoreASecret() {
        signIn();

        assertThatCode(() -> auditTrail.record(FIRM, "identity.password.reset_requested", "USER",
                ENTITY, Instant.now(), "reset with password=Hunter2!!", EVENT_ID))
                .doesNotThrowAnyException();

        verify(auditEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a duplicate event id is what the unique index is for, not an error to propagate")
    void swallowsADuplicate() {
        signIn();
        when(auditEventRepository.save(any(AuditEvent.class)))
                .thenThrow(new DataIntegrityViolationException("uk_audit_events_source"));

        assertThatCode(() -> auditTrail.record(FIRM, "invoice.issued", "INVOICE", ENTITY,
                Instant.now(), "Invoice INV-2026-000001 issued", EVENT_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an audit failure never breaks the business operation that caused it")
    void swallowsAnyFailure() {
        signIn();
        when(auditEventRepository.save(any(AuditEvent.class)))
                .thenThrow(new IllegalStateException("the database went away"));

        assertThatCode(() -> auditTrail.record(FIRM, "invoice.issued", "INVOICE", ENTITY,
                Instant.now(), "Invoice INV-2026-000001 issued", EVENT_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void defaultsTheOccurrenceTimeRatherThanStoringNull() {
        auditTrail.record(FIRM, "invoice.created", "INVOICE", ENTITY, null, "Drafted", EVENT_ID);

        assertThat(recordAndCapture().getOccurredAt()).isNotNull();
    }
}
