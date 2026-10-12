package com.bigbazaar.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/admin/audit-events")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('AMR_MFA')")
@Tag(name = "Audit")
public class AuditController {

    private final AuditEventRepository repository;
    private final JsonMapper jsonMapper;

    public AuditController(AuditEventRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Search the audit log; every filter is optional, newest first")
    public Page<AuditEventResponse> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (entityType != null) {
                where.add(cb.equal(root.get("entityType"), entityType));
            }
            if (entityId != null) {
                where.add(cb.equal(root.get("entityId"), entityId));
            }
            if (actorId != null) {
                where.add(cb.equal(root.get("actorId"), actorId));
            }
            if (eventType != null) {
                where.add(cb.equal(root.get("eventType"), eventType));
            }
            if (correlationId != null) {
                where.add(cb.equal(root.get("correlationId"), correlationId));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("occurredAt"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable).map(this::toResponse);
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(e.getEventId(), e.getEventType(), e.getEventVersion(), e.getSourceService(),
                e.getActorId(), e.getEntityType(), e.getEntityId(), e.getReason(), e.getCorrelationId(),
                e.getOccurredAt(), e.getRecordedAt(), jsonMapper.readTree(e.getDetails()));
    }

    public record AuditEventResponse(UUID eventId, String eventType, int eventVersion, String sourceService,
                                     UUID actorId, String entityType, String entityId, String reason,
                                     UUID correlationId, Instant occurredAt, Instant recordedAt, JsonNode details) {
    }
}
