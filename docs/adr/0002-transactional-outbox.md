# ADR 0002: Transactional outbox for every published event

- Status: Accepted
- Date: 2026-10-12

## Context

A service that writes to PostgreSQL and then sends to Kafka can lose the event (crash after commit)
or publish an event for a change that rolled back (send before commit). Payment and seller approval
flows can't tolerate either.

## Decision

Services write the event envelope into an `outbox_events` table in the same transaction as the
business change. A scheduled relay in each service locks pending rows with `FOR UPDATE SKIP LOCKED`,
publishes them keyed by aggregate ID, and marks them published. After a configurable number of failed
attempts a row is parked as `FAILED`, which is our dead-letter state on the producer side.

Every consumer records processed `eventId`s in `processed_events` and ignores repeats, and failed
messages go to `<topic>.DLT` after retries.

## Consequences

- Delivery is at-least-once, so consumers must be idempotent.
- Events arrive up to one poll interval (1 s default) after commit.
- Ordering holds per aggregate because each aggregate maps to one partition and the relay stops a
  batch at the first failure.
- Debezium CDC could replace the polling relay later without changing the table or the consumers.
