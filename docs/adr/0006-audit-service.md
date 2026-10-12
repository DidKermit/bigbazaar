# ADR 0006: Audit service as the first event consumer

- Status: Accepted
- Date: 2026-10-12

## Context

The SRS (FR-AUD-01/02, 8.4) asks for a durable record of sensitive actions: who did what, to what,
why, and with which correlation ID. Identity already writes every such action as an event through
the outbox. Phase 2 services will add more.

## Decision

- A small `audit-service` (port 8091, `platform_db`) consumes domain topics and writes one row per
  event into `audit_events`. It works on the generic `EventEnvelope` shape, so new event types are
  recorded without code changes; new topics are one line in the listener.
- Personal fields (`email`, `phone`, `taxId`, tokens, passwords) are dropped before storing.
- Idempotent: a `processed_events(consumer, event_id)` row and the audit row commit in one
  transaction, so redelivered events are skipped.
- Errors: transient failures are retried 3 times one second apart; malformed messages skip retries.
  Either way the message goes to `<topic>.DLT` with the exception in its headers, and the partition
  keeps moving.
- `audit_events` is append-only: a database trigger rejects UPDATE and DELETE.
- Admins with an MFA login search it at `GET /api/v1/admin/audit-events` by entity, actor, event
  type, correlation ID and time range.

## Consequences

- Identity stays unaware of auditing; it just publishes events.
- The audit log is eventually consistent: an action appears there after Kafka delivers it, usually
  within a second.
- This service sets the pattern (idempotent consumer, DLT, correlation IDs in logs) every later
  consumer copies.
