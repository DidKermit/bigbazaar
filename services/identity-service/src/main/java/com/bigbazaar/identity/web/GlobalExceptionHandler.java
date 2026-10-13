package com.bigbazaar.identity.web;

import com.bigbazaar.common.web.ApiError;
import com.bigbazaar.common.web.Headers;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to the standard error body (SRS 12.1) without leaking internals. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException e) {
        return respond(e.code().status(), e.code().name(), e.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(), "Request validation failed.",
                fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(), "Malformed request.", List.of());
    }

    @ExceptionHandler(com.bigbazaar.identity.kyc.KycUnavailableException.class)
    ResponseEntity<ApiError> kycUnavailable(com.bigbazaar.identity.kyc.KycUnavailableException e) {
        log.warn("KYC provider unavailable: {}", e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.KYC_UNAVAILABLE.name(),
                "The verification service is unavailable. Please try again in a few minutes.", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied(AccessDeniedException e) {
        return respond(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED.name(),
                "You do not have access to this resource.", List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return respond(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION.name(),
                "The record was changed by someone else. Reload and try again.", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return respond(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION.name(),
                "The request conflicts with existing data.", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "Something went wrong. Quote the correlation ID when reporting this.", List.of());
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                                    List<ApiError.FieldError> fields) {
        return ResponseEntity.status(status)
                .body(ApiError.of(code, message, MDC.get(Headers.MDC_CORRELATION_ID), fields));
    }
}
