package com.juriscore.common.error;

import com.juriscore.common.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return status(ErrorCode.DUPLICATE_RESOURCE, null);
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
        String incidentId = UUID.randomUUID().toString();
        log.error("Unhandled exception [incident={}] on {} {}", incidentId, request.getMethod(),
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
