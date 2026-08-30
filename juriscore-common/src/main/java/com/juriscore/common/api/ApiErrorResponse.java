package com.juriscore.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standard error envelope.
 *
 * <pre>
 * { "success": false, "error": { "code": "CASE_NOT_FOUND", "message": "Case does not exist" } }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(boolean success, ErrorBody error) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(false, new ErrorBody(code, message, null, Instant.now()));
    }

    public static ApiErrorResponse of(String code, String message, List<FieldViolation> details) {
        return new ApiErrorResponse(false, new ErrorBody(code, message, details, Instant.now()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, List<FieldViolation> details, Instant timestamp) {
    }

    public record FieldViolation(String field, String message) {
    }
}
