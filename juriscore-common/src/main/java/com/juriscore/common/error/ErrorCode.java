package com.juriscore.common.error;

import org.springframework.http.HttpStatus;

/**
 * The single registry of machine-readable error codes returned by the API.
 * Clients switch on {@code code}; {@code defaultMessage} is human-facing only.
 */
public enum ErrorCode {

    // 400
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body could not be parsed"),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "Invalid argument"),
    WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "Password does not meet the minimum requirements"),

    // 401
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token is invalid"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or has been revoked"),
    ACCOUNT_INACTIVE(HttpStatus.UNAUTHORIZED, "Account is not active"),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "Account is locked"),

    // 403
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
    TENANT_MISMATCH(HttpStatus.FORBIDDEN, "Resource belongs to a different organization"),

    // 404
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User does not exist"),
    ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Organization does not exist"),
    CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Client does not exist"),
    CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "Case does not exist"),
    HEARING_NOT_FOUND(HttpStatus.NOT_FOUND, "Hearing does not exist"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "Task does not exist"),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Document does not exist"),
    INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND, "Invoice does not exist"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource does not exist"),

    // 405 / 415
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this endpoint"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Media type is not supported"),

    // 409
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "An account with this email already exists"),
    ORGANIZATION_SLUG_TAKEN(HttpStatus.CONFLICT, "That organization identifier is already in use"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Resource already exists"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT,
            "This record was modified by someone else. Reload and try again."),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, "That state transition is not allowed"),

    // 429
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please slow down."),

    // 500
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
