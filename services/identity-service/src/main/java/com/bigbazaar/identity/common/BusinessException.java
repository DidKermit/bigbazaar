package com.bigbazaar.identity.common;

/** A rule violation that maps to a stable error code in the standard error response. */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
