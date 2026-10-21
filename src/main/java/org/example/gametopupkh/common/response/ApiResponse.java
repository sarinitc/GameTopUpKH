package org.example.gametopupkh.common.response;

import java.time.Instant;

/**
 * Envelope returned by every REST endpoint.
 *
 * @param success   whether the call succeeded
 * @param code      machine readable result code
 * @param message   human readable message
 * @param data      payload, {@code null} on failures
 * @param timestamp instant the response was produced
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "Success", data, Instant.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", "Success", null, Instant.now());
    }
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null, Instant.now());
    }
}
