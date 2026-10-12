package com.bigbazaar.identity.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    SELLER_NOT_FOUND(HttpStatus.NOT_FOUND),
    SELLER_APPLICATION_EXISTS(HttpStatus.CONFLICT),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
