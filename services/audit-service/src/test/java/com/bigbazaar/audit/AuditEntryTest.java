package com.bigbazaar.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditEntryTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void picksTheMostSpecificEntityAndActor() {
        UUID admin = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        var entry = AuditEntry.from(json.readTree("""
                {"eventId":"%s","eventType":"SellerApproved","eventVersion":1,"sourceService":"identity-service",
                 "occurredAt":"2026-10-12T10:00:00Z","correlationId":"%s",
                 "payload":{"sellerId":"%s","userId":"%s","actorId":"%s","reason":"Docs verified"}}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), seller, UUID.randomUUID(), admin)));
        assertThat(entry.entityType()).isEqualTo("Seller");
        assertThat(entry.entityId()).isEqualTo(seller.toString());
        assertThat(entry.actorId()).isEqualTo(admin);
        assertThat(entry.reason()).isEqualTo("Docs verified");
    }

    @Test
    void selfServiceEventsUseTheUserAsActorAndDropPersonalData() {
        UUID user = UUID.randomUUID();
        var entry = AuditEntry.from(json.readTree("""
                {"eventId":"%s","eventType":"UserRegistered","eventVersion":1,"sourceService":"identity-service",
                 "occurredAt":"2026-10-12T10:00:00Z",
                 "payload":{"userId":"%s","email":"kai@example.com","phone":"9876543210","roles":["CUSTOMER"]}}"""
                .formatted(UUID.randomUUID(), user)));
        assertThat(entry.entityType()).isEqualTo("User");
        assertThat(entry.actorId()).isEqualTo(user);
        assertThat(entry.details().has("email")).isFalse();
        assertThat(entry.details().has("phone")).isFalse();
        assertThat(entry.details().has("roles")).isTrue();
    }

    @Test
    void rejectsEnvelopesMissingRequiredFields() {
        assertThatThrownBy(() -> AuditEntry.from(json.readTree("""
                {"eventType":"X","sourceService":"s","occurredAt":"2026-10-12T10:00:00Z","payload":{}}""")))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> AuditEntry.from(json.readTree("""
                {"eventId":"not-a-uuid","eventType":"X","sourceService":"s","occurredAt":"2026-10-12T10:00:00Z",
                 "payload":{}}""")))
                .isInstanceOf(MalformedEventException.class);
    }
}
