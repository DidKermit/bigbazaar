package com.bigbazaar.audit;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One audit row derived from any service's {@code EventEnvelope}. Works on the envelope's generic
 * shape, so new event types from later services are recorded without code changes here.
 */
record AuditEntry(UUID eventId, String eventType, int eventVersion, String sourceService, UUID actorId,
                  String entityType, String entityId, String reason, UUID correlationId, Instant occurredAt,
                  ObjectNode details) {

    /** First match wins: the most specific business object the event is about. */
    private static final List<String[]> ENTITY_FIELDS = List.of(
            new String[] {"documentId", "SellerDocument"},
            new String[] {"sellerId", "Seller"},
            new String[] {"productId", "Product"},
            new String[] {"orderId", "Order"},
            new String[] {"paymentId", "Payment"},
            new String[] {"reviewId", "Review"},
            new String[] {"userId", "User"});

    /** Kept out of the audit log (FR-AUD-02: changed values only "where safe"). */
    private static final Set<String> SENSITIVE = Set.of("email", "phone", "password", "passwordHash", "token",
            "secret", "accessToken", "refreshToken", "taxId");

    static AuditEntry from(JsonNode envelope) {
        try {
            UUID eventId = UUID.fromString(text(envelope, "eventId", true));
            String eventType = text(envelope, "eventType", true);
            String source = text(envelope, "sourceService", true);
            Instant occurredAt = Instant.parse(text(envelope, "occurredAt", true));
            int version = envelope.path("eventVersion").asInt(1);
            String correlation = text(envelope, "correlationId", false);
            JsonNode payload = envelope.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new MalformedEventException("payload must be an object");
            }
            ObjectNode details = ((ObjectNode) payload).deepCopy();
            SENSITIVE.forEach(details::remove);

            String entityType = "Unknown";
            String entityId = eventId.toString();
            for (String[] candidate : ENTITY_FIELDS) {
                String value = text(payload, candidate[0], false);
                if (value != null) {
                    entityType = candidate[1];
                    entityId = value;
                    break;
                }
            }
            // Self-service events (registration) have no separate actor: the user acted on themselves.
            String actor = text(payload, "actorId", false);
            if (actor == null) {
                actor = text(payload, "userId", false);
            }
            return new AuditEntry(eventId, eventType, version, source, actor == null ? null : UUID.fromString(actor),
                    entityType, entityId, text(payload, "reason", false),
                    correlation == null ? null : UUID.fromString(correlation), occurredAt, details);
        } catch (MalformedEventException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MalformedEventException("Unreadable envelope: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asString().isBlank()) {
            if (required) {
                throw new MalformedEventException("missing " + field);
            }
            return null;
        }
        return value.asString();
    }
}
