package com.bigbazaar.identity.kyc;

/** The provider or the government source behind it is down or slow; the check is retried later. */
public class KycUnavailableException extends RuntimeException {

    public KycUnavailableException(String message) {
        super(message);
    }

    public KycUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
