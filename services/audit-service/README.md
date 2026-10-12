# audit-service

Keeps an append-only record of every sensitive action on the platform. Database: `platform_db`.
Consumes `identity-events` (later phases add their topics). Port 8091.

## API (`/api/v1`)

| Method | Path | Who | Purpose | SRS |
|---|---|---|---|---|
| GET | `/admin/audit-events` | admin with MFA login | Search, newest first. Filters: `entityType`, `entityId`, `actorId`, `eventType`, `correlationId`, `from`, `to`; `page`, `size` (max 100) | FR-AUD-01, 02 |

Each entry has the event type, who acted (`actorId`), what it was about (`entityType` + `entityId`,
for example `Seller` and its ID), the reason, the correlation ID, when it happened and the event
details without personal data.

## How an event becomes an entry

1. `DomainEventListener` reads the message and parses the envelope (`AuditEntry.from`).
2. The entity is the most specific ID in the payload: document, seller, product, order, payment,
   review, then user. The actor is `actorId`, or the user for self-service events like registration.
3. `AuditRecorder` inserts a `processed_events` marker and the audit row in one transaction. If the
   marker already exists the event is a redelivery and is skipped.

## Failure handling

| Failure | Behaviour |
|---|---|
| Same event delivered twice | Recorded once (`processed_events`). |
| Message isn't JSON or lacks `eventId`, `eventType`, `sourceService`, `occurredAt` or `payload` | Sent straight to `identity-events.DLT` with the error in its headers. |
| Database briefly down | Retried 3 times one second apart, then sent to the DLT. Replay from the DLT once fixed. |
| Audit service down | Events wait in Kafka; it catches up from its committed offset on restart. |
| Someone tries to edit history | A trigger rejects UPDATE and DELETE on `audit_events`. |

## Configuration

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_JWK_SET_URI`, `JWT_ISSUER`.
