package com.bigbazaar.common.events;

/** Kafka topic names from the SRS event catalog (9.3). */
public final class Topics {
    public static final String IDENTITY_EVENTS = "identity-events";
    public static final String PRODUCT_EVENTS = "product-events";
    public static final String COMMERCE_EVENTS = "commerce-events";
    public static final String PAYMENT_EVENTS = "payment-events";
    public static final String TRUST_EVENTS = "trust-events";
    public static final String RECOMMENDATION_EVENTS = "recommendation-events";
    public static final String NOTIFICATION_EVENTS = "notification-events";

    /** Dead-letter topic convention: {@code <topic>.DLT}. */
    public static String deadLetter(String topic) {
        return topic + ".DLT";
    }

    private Topics() {
    }
}
