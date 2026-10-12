package com.bigbazaar.common.web;

import java.time.Instant;
import java.util.List;

/** Standard error body returned by every service (SRS 12.1). */
public record ApiError(
        boolean success,
        String code,
        String message,
        String correlationId,
        Instant timestamp,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message, String correlationId) {
        return new ApiError(false, code, message, correlationId, Instant.now(), List.of());
    }

    public static ApiError of(String code, String message, String correlationId, List<FieldError> fieldErrors) {
        return new ApiError(false, code, message, correlationId, Instant.now(), List.copyOf(fieldErrors));
    }
}
