package com.bigbazaar.identity.common;

import com.bigbazaar.common.web.Headers;
import java.util.UUID;
import org.slf4j.MDC;

public final class CorrelationIds {

    /** The current request's correlation ID, or null outside a request (e.g. scheduled jobs). */
    public static UUID current() {
        String value = MDC.get(Headers.MDC_CORRELATION_ID);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private CorrelationIds() {
    }
}
