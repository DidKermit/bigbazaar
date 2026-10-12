package com.bigbazaar.common.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void ofFillsIdAndTimestamp() {
        var correlationId = UUID.randomUUID();
        var envelope = EventEnvelope.of("UserRegistered", 1, "identity-service", correlationId, "payload");

        assertNotNull(envelope.eventId());
        assertNotNull(envelope.occurredAt());
        assertEquals(correlationId, envelope.correlationId());
    }

    @Test
    void rejectsVersionBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> EventEnvelope.of("UserRegistered", 0, "identity-service", null, "payload"));
    }
}
