package com.juriscore.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard success envelope for every JurisCore endpoint.
 *
 * <pre>
 * { "success": true, "data": { ... }, "message": "Case created successfully" }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message);
    }

    public static ApiResponse<Void> message(String message) {
        return new ApiResponse<>(true, null, message);
    }
}
