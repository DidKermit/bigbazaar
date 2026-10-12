package com.bigbazaar.common.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Standard wrapper for every Kafka event (SRS 9.1). Consumers deduplicate on {@code eventId}
 * and propagate {@code correlationId} into their own logs and outgoing events.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        String sourceService,
        UUID correlationId,
        Instant occurredAt,
        T payload
) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(sourceService, "sourceService");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
    }

    public static <T> EventEnvelope<T> of(String eventType, int eventVersion, String sourceService,
                                          UUID correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, eventVersion, sourceService,
                correlationId, Instant.now(), payload);
    }
}
