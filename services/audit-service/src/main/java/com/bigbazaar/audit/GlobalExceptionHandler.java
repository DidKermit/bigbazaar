package com.bigbazaar.audit;

import com.bigbazaar.common.web.ApiError;
import com.bigbazaar.common.web.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParam(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid value for " + e.getName() + ".");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have access to this resource.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Quote the correlation ID when reporting this.");
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message, MDC.get(Headers.MDC_CORRELATION_ID)));
    }
}
