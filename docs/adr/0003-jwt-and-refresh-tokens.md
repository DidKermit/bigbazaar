# ADR 0003: RS256 access tokens with rotating refresh tokens

- Status: Accepted
- Date: 2026-10-12

## Context

Services must stay stateless behind a load balancer (SRS 14.2), so sessions can't live in memory.
Every service needs to verify tokens, but only identity should be able to create them. Admins must
be able to cut off a suspended account quickly.

## Decision

- Identity signs access tokens with an RSA private key (RS256, 2048+ bits). The key file is mounted
  into identity only; its public half is derived at startup and published at
  `/.well-known/jwks.json`. The gateway and later services verify against that JWKS, so a leaked
  verifier can't mint tokens.
- The JWT header `kid` is the key's RFC 7638 thumbprint. Verifiers refetch the JWKS when they see an
  unknown `kid`, which is how a rotated key is picked up without restarts.
- Access tokens last 15 minutes and carry `sub`, `email`, `roles`, `amr` and `iss`.
- Refresh tokens are random 256-bit values stored only as SHA-256 hashes, valid for 14 days and
  rotated on every use. Tokens from one login form a family; presenting an already-rotated token
  revokes the family. Each refresh token records whether the login passed MFA.
- Suspension revokes all refresh tokens and identity refuses suspended users on every protected call,
  so the remaining exposure is at most one access-token lifetime on other services.

## Consequences

- No shared secret to distribute. Only one file (the private key) needs protecting.
- Today's rotation is a hard switch: tokens signed with the old key fail once identity restarts with
  a new one, so users re-authenticate within one access-token lifetime. Publishing the next key in the
  JWKS ahead of time (overlap rotation) is the next step once there are production users.
- Role changes (seller approval) appear in the next access token, within 15 minutes.
