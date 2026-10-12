# ADR 0003: Short-lived JWTs with rotating refresh tokens

- Status: Accepted
- Date: 2026-10-12

## Context

Services must stay stateless behind a load balancer (SRS 14.2), so sessions can't live in memory.
Admins must still be able to cut off a suspended account quickly.

## Decision

- Identity issues HS256 access tokens valid for 15 minutes with `sub`, `email`, `roles` and `iss`.
  The gateway checks signature, expiry and issuer at the edge; each service checks again and applies
  role and ownership rules.
- Refresh tokens are random 256-bit values stored only as SHA-256 hashes, valid for 14 days and
  rotated on every use. Tokens from one login form a family; presenting an already-rotated token
  revokes the family, which catches stolen tokens.
- Suspension revokes all refresh tokens and identity refuses suspended users on every protected call,
  so the remaining exposure is at most one access-token lifetime on other services.

## Consequences

- One shared secret must be distributed to the gateway and every service. Moving to RS256 with a
  JWKS endpoint on identity removes that, and is the planned upgrade once more services verify tokens.
- Role changes (seller approval) appear in the next access token, within 15 minutes.
