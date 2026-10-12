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
- Rotation overlaps (added 2026-10-12): `infra/keys/rotate-signing-key.sh` saves the old public key and
  creates a new private key. Identity then signs with the new key while still publishing and accepting
  the old one (`JWT_PREVIOUS_PUBLIC_KEYS`). Verifiers see the new `kid` and refetch the JWKS. After one
  access-token lifetime the old key is removed. Nobody is logged out. A suspected key leak is the
  exception: rotate without keeping the old key, which logs everyone out by design.
- Role changes (seller approval) appear in the next access token, within 15 minutes.
