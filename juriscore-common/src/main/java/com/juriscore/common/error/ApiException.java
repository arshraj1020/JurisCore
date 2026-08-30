package com.juriscore.common.error;

/**
 * Base exception for every expected (non-bug) failure. Carries the {@link ErrorCode}
 * so the handler never has to guess an HTTP status.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static ApiException notFound(ErrorCode code, Object id) {
        return new ApiException(code, code.defaultMessage() + " [" + id + "]");
    }
}
