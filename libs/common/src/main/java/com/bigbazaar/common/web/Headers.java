package com.bigbazaar.common.web;

/** HTTP header and MDC key names shared by the gateway and services. */
public final class Headers {
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String MDC_CORRELATION_ID = "correlationId";

    private Headers() {
    }
}
