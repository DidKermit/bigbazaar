package com.bigbazaar.audit;

/** The message can never be processed (not JSON, missing fields). Skips retries and goes to the DLT. */
class MalformedEventException extends RuntimeException {

    MalformedEventException(String message) {
        super(message);
    }

    MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
