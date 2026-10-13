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
    MFA_ALREADY_ENABLED(HttpStatus.CONFLICT),
    MFA_NOT_ENABLED(HttpStatus.CONFLICT),
    MFA_SETUP_NOT_STARTED(HttpStatus.CONFLICT),
    INVALID_MFA_CODE(HttpStatus.UNAUTHORIZED),
    INVALID_MFA_CHALLENGE(HttpStatus.UNAUTHORIZED),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    UPLOAD_NOT_FOUND(HttpStatus.CONFLICT),
    INVALID_DOCUMENT(HttpStatus.UNPROCESSABLE_ENTITY),
    KYC_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
