# ADR 0004: Multi-factor authentication with authenticator apps

- Status: Accepted
- Date: 2026-10-12

## Context

Admin accounts approve sellers, suspend users and will override payments. A phished password alone
should not be enough to do that. Customers and sellers should be able to protect their accounts too.

## Decision

- Second factor: TOTP (RFC 6238) in any authenticator app (Google Authenticator, Microsoft
  Authenticator, 1Password, Authy). It needs no SMS or email provider, works offline and costs nothing.
- Enrollment is two steps: a password-confirmed setup returns the secret and `otpauth://` URI for a QR
  code, then the first valid code activates MFA and returns 10 single-use recovery codes once.
- Login with MFA on is two steps: the password returns a short-lived challenge (5 minutes, 5 attempts),
  and the challenge plus a code returns tokens. Access tokens carry `amr: ["pwd","mfa"]`.
- Policy: required for ADMIN (and SUPPORT, once support endpoints exist); optional for customers and
  sellers. Admins can still log in with a password to enroll, but every `/admin/**` call returns
  `403 MFA_REQUIRED` until their token shows `mfa`.
- TOTP secrets are encrypted with AES-256-GCM using `MFA_ENCRYPTION_KEY`, with the user ID as
  associated data. Each accepted time step is stored so a code can't be replayed.
- Lost phone: use a recovery code, or an admin resets MFA with a recorded reason (event `MfaReset`).
  Enabling, disabling or resetting MFA signs out every existing session.

## Consequences

- Admin onboarding gains one step; the bootstrap admin must enroll before approving sellers.
- `MFA_ENCRYPTION_KEY` becomes a secret to protect and back up: losing it disables every enrolled
  authenticator. The `v1:` ciphertext prefix leaves room for key rotation.
- WebAuthn passkeys can be added later as another factor behind the same challenge step.
