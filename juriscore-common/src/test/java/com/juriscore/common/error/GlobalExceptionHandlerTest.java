package com.juriscore.common.error;

import com.juriscore.common.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which database refusals are the caller's problem, and which are ours.
 *
 * <p>Every {@link DataIntegrityViolationException} used to become {@code DUPLICATE_RESOURCE}
 * — HTTP 409, "that already exists". For a unique-constraint violation that is exactly
 * right. For the other members of SQL's integrity-violation class it is wrong twice over:
 * the user is told to rename something that has no name conflict, and a genuine defect
 * (a not-null column the service left unset, a foreign key pointing at nothing, a check
 * constraint the service failed to enforce) is filed under "routine conflict" where nobody
 * watching error rates will look at it.
 *
 * <p>These tests pin the split, and the incident id that now accompanies the half of it
 * that is a bug.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("POST", "/api/v1/clients");
        request = mockRequest;
    }

    /** A DataIntegrityViolationException wrapping a driver error with the given SQLState. */
    private static DataIntegrityViolationException withSqlState(String sqlState, String message) {
        return new DataIntegrityViolationException(message,
                new SQLException(message, sqlState));
    }

    private static ApiErrorResponse bodyOf(ResponseEntity<ApiErrorResponse> response) {
        ApiErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    @Nested
    @DisplayName("a genuine duplicate")
    class Duplicates {

        @Test
        @DisplayName("SQLState 23505 is reported as a duplicate the user can resolve")
        void uniqueViolationIsADuplicate() {
            ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(
                    withSqlState("23505", "duplicate key value violates unique constraint"),
                    request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bodyOf(response).error().code()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE.name());
        }

        @Test
        @DisplayName("Spring's own DuplicateKeyException is recognised without reading a SQLState")
        void duplicateKeyExceptionIsADuplicate() {
            // The translated form, when Spring recognises the driver's error. It carries no
            // SQLException of its own here, so the subclass check is the only signal.
            ResponseEntity<ApiErrorResponse> response =
                    handler.handleDataIntegrity(new DuplicateKeyException("already exists"), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bodyOf(response).error().code()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE.name());
        }

        @Test
        @DisplayName("the SQLState is found even when it is buried in the cause chain")
        void findsSqlStateThroughNestedCauses() {
            // Hibernate wraps the driver exception in its own before Spring wraps that, so
            // the state is rarely on the immediate cause.
            DataIntegrityViolationException nested = new DataIntegrityViolationException(
                    "could not execute statement",
                    new IllegalStateException("hibernate wrapper",
                            new SQLException("duplicate key", "23505")));

            assertThat(handler.handleDataIntegrity(nested, request).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("nothing about the schema reaches the caller")
        void doesNotLeakTheConstraintName() {
            ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(
                    withSqlState("23505",
                            "duplicate key value violates unique constraint \"uk_clients_email_lower\""),
                    request);

            // The constraint name describes the schema to whoever is probing it.
            assertThat(bodyOf(response).error().message())
                    .doesNotContain("uk_clients_email_lower")
                    .doesNotContain("duplicate key value");
        }
    }

    @Nested
    @DisplayName("an integrity violation that is actually a defect")
    class Defects {

        @Test
        @DisplayName("a not-null violation is a server error, not a duplicate")
        void notNullViolationIsInternal() {
            ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(
                    withSqlState("23502", "null value in column \"display_name\""), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(bodyOf(response).error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        }

        @Test
        @DisplayName("a foreign key violation is a server error")
        void foreignKeyViolationIsInternal() {
            ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(
                    withSqlState("23503", "violates foreign key constraint"), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("a check constraint violation is a server error")
        void checkViolationIsInternal() {
            // The service was supposed to enforce this before the write reached the database.
            ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(
                    withSqlState("23514", "violates check constraint \"ck_invoices_total\""), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("an integrity violation with no SQLState at all is a server error")
        void unknownIntegrityViolationIsInternal() {
            ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(
                    new DataIntegrityViolationException("something went wrong"), request);

            // Not knowing why the database refused is not evidence that the user caused it.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("the caller gets an incident id and nothing else")
        void carriesAnIncidentId() {
            ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(
                    withSqlState("23502", "null value in column \"organization_id\""), request);

            String message = bodyOf(response).error().message();
            // The id is the only thing connecting what the user saw to the logged stack.
            assertThat(message).contains("incident ");
            assertThat(message)
                    .doesNotContain("organization_id")
                    .doesNotContain("SQLException");
        }
    }

    @Nested
    @DisplayName("unhandled exceptions")
    class Unhandled {

        @Test
        @DisplayName("are 500 with an incident id and no internal detail")
        void areGenericWithAnIncidentId() {
            ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
                    new IllegalStateException("connection pool exhausted at com.zaxxer.hikari"),
                    request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            String message = bodyOf(response).error().message();
            assertThat(message).contains("incident ");
            assertThat(message).doesNotContain("hikari").doesNotContain("IllegalStateException");
        }

        @Test
        @DisplayName("get a different incident id each time, so two reports are two incidents")
        void incidentIdsAreUnique() {
            String first = bodyOf(handler.handleUnexpected(new RuntimeException("a"), request))
                    .error().message();
            String second = bodyOf(handler.handleUnexpected(new RuntimeException("b"), request))
                    .error().message();

            assertThat(first).isNotEqualTo(second);
        }
    }
}
