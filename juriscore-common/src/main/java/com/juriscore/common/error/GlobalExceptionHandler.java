package com.juriscore.common.error;

import com.juriscore.common.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Turns every exception into the documented error envelope.
 *
 * <p>Rule of the house: unexpected exceptions are logged with a correlation id and
 * reported as {@code INTERNAL_ERROR}. Stack traces and messages from unknown
 * exceptions never reach the client — legal data leaks through error strings too.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** SQLState 23505, "unique_violation" — the only integrity error a user can resolve. */
    private static final String UNIQUE_VIOLATION = "23505";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("API error {} on {} {}", code, request.getMethod(), request.getRequestURI(), ex);
        } else {
            log.debug("API error {} on {} {}: {}", code, request.getMethod(), request.getRequestURI(),
                    ex.getMessage());
        }
        return ResponseEntity.status(code.status()).body(ApiErrorResponse.of(code.name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<ApiErrorResponse.FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        return status(ErrorCode.VALIDATION_FAILED, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiErrorResponse.FieldViolation> details = ex.getConstraintViolations().stream()
                .map(v -> new ApiErrorResponse.FieldViolation(
                        v.getPropertyPath() == null ? null : v.getPropertyPath().toString(),
                        v.getMessage()))
                .toList();
        return status(ErrorCode.VALIDATION_FAILED, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return status(ErrorCode.MALFORMED_REQUEST, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.status())
                .body(ApiErrorResponse.of(ErrorCode.INVALID_ARGUMENT.name(),
                        "Parameter '" + ex.getName() + "' has an invalid value"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return status(ErrorCode.METHOD_NOT_ALLOWED, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return status(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        return status(ErrorCode.RESOURCE_NOT_FOUND, null);
    }

    /**
     * Spring 6 reports an unmatched path as {@code NoResourceFoundException}, which is a
     * {@code ServletException} and so would otherwise reach the catch-all below: every
     * request for a mistyped URL would answer 500 and write an ERROR with a stack trace
     * and an incident id. Alert fatigue is a security problem — the noise is what hides
     * the one entry that mattered.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return status(ErrorCode.RESOURCE_NOT_FOUND, null);
    }

    /**
     * A missing or unconvertible query parameter is the caller's mistake, not ours —
     * {@code PATCH /users/{id}/status} with no {@code status} belongs in the 400 family.
     * These are {@code ServletException}s too, and would otherwise be reported as 500.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiErrorResponse> handleBindingFailure(ServletRequestBindingException ex) {
        String detail = ex instanceof MissingServletRequestParameterException missing
                ? "Required parameter '" + missing.getParameterName() + "' is missing"
                : ErrorCode.INVALID_ARGUMENT.defaultMessage();
        return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.status())
                .body(ApiErrorResponse.of(ErrorCode.INVALID_ARGUMENT.name(), detail));
    }

    /** Optimistic locking: two lawyers edited the same case. See PRD §41.1. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return status(ErrorCode.CONCURRENT_MODIFICATION, null);
    }

    /**
     * A write the database refused — but only one kind of refusal is the user's fault.
     *
     * <p>This used to answer {@code DUPLICATE_RESOURCE} for every
     * {@link DataIntegrityViolationException}, which is wrong in both directions. A
     * not-null violation, a foreign key pointing at a row that is not there, or a check
     * constraint the service failed to enforce are all <em>defects</em>: the application
     * sent the database something it should never have sent. Reporting them as 409 "that
     * already exists" tells the user to rename something that has no name conflict, and it
     * hides the defect from whoever is watching error rates, because a 409 reads as a
     * routine conflict rather than a bug.
     *
     * <p>So the SQLState decides. {@code 23505} — unique violation — is the genuine
     * duplicate, and the only one a different value would fix. Everything else is treated
     * exactly like an unhandled exception: 500, an incident id, and the full stack in the
     * log where it belongs.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                                HttpServletRequest request) {
        if (isUniqueViolation(ex)) {
            log.warn("Unique constraint violation on {} {}", request.getMethod(),
                    request.getRequestURI());
            return status(ErrorCode.DUPLICATE_RESOURCE, null);
        }
        return internalError(ex, request, "Data integrity violation");
    }

    /**
     * Whether the database refused this write because something was already there.
     *
     * <p>Two signals, because neither is complete on its own. Spring translates a unique
     * violation to {@link DuplicateKeyException} when it recognises the driver's error, and
     * that subclass is the clearest statement of intent available. When translation does
     * not produce it — Hibernate wraps its own constraint exception, and the driver's code
     * survives only on the underlying {@link SQLException} — the SQLState is read directly
     * from the cause chain. {@code 23505} is "unique_violation" in the SQL standard's class
     * 23 (integrity constraint violation), and its siblings are deliberately excluded:
     * {@code 23502} not-null, {@code 23503} foreign key and {@code 23514} check are all
     * application defects rather than user conflicts.
     */
    private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return status(ErrorCode.ACCESS_DENIED, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        return status(ErrorCode.UNAUTHENTICATED, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return internalError(ex, request, "Unhandled exception");
    }

    /**
     * The one way an unexpected failure reaches a caller: a generic message and an incident
     * id, with everything else kept in the log.
     *
     * <p>The id is the whole point of the pair. It is the only thing that connects what the
     * user saw to the stack trace that explains it, and it is the only internal detail that
     * crosses the boundary — no exception class, no SQL, no constraint name, since those
     * describe the schema to whoever is probing it.
     */
    private ResponseEntity<ApiErrorResponse> internalError(Exception ex, HttpServletRequest request,
                                                           String what) {
        String incidentId = UUID.randomUUID().toString();
        log.error("{} [incident={}] on {} {}", what, incidentId, request.getMethod(),
                request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR.name(),
                        ErrorCode.INTERNAL_ERROR.defaultMessage() + " (incident " + incidentId + ")"));
    }

    private ApiErrorResponse.FieldViolation toViolation(FieldError error) {
        return new ApiErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<ApiErrorResponse> status(ErrorCode code,
                                                    List<ApiErrorResponse.FieldViolation> details) {
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code.name(), code.defaultMessage(), details));
    }
}
