# identity-service

Owns users, roles, addresses, refresh tokens and seller onboarding. Database: `identity_db`.
Publishes to the `identity-events` topic.

## API (`/api/v1`)

| Method | Path | Who | Purpose | SRS |
|---|---|---|---|---|
| POST | `/auth/register` | public | Create a customer account | FR-AUTH-01, 02 |
| POST | `/auth/login` | public | Email + password. Returns tokens, or an MFA challenge when MFA is on | FR-AUTH-03 |
| POST | `/auth/mfa/verify` | public | Challenge + authenticator code (or recovery code) to tokens | |
| POST | `/auth/refresh` | public | Rotate a refresh token | FR-AUTH-04 |
| POST | `/auth/logout` | public | Revoke a refresh token's chain | FR-AUTH-04 |
| GET | `/users/me` | any user | Profile and roles | FR-AUTH-05 |
| GET/POST/DELETE | `/users/me/addresses` | any user | Address book (soft delete) | |
| GET | `/users/me/mfa` | any user | MFA status and recovery codes left | |
| POST | `/users/me/mfa/totp/setup` | any user | Password-confirmed; returns secret + `otpauth://` URI for the QR | |
| POST | `/users/me/mfa/totp/activate` | any user | First code from the app; enables MFA, returns 10 recovery codes once | |
| POST | `/users/me/mfa/recovery-codes` | any user | Replace recovery codes (needs a current code) | |
| POST | `/users/me/mfa/disable` | any user | Password + code; signs out every session | |
| GET | `/.well-known/jwks.json` | public | Public signing key(s) for token verification | |
| POST | `/sellers/applications` | customer | Apply, or re-apply after rejection | FR-SELLER-01 |
| GET | `/sellers/me` | applicant | Application, verification checks, documents, status history | FR-SELLER-05 |
| POST | `/sellers/me/verification/identity` | applicant | Start DigiLocker; returns the session ID for the provider's SDK | ADR 0007 |
| POST | `/sellers/me/verification/identity/complete` | applicant | DigiLocker done: run the automatic checks | ADR 0007 |
| POST | `/sellers/me/documents/uploads` | applicant | Ask for a short-lived upload link (PDF, JPEG or PNG, up to 10 MB) | SRS 2.5 |
| POST | `/sellers/me/documents/{id}/confirm` | applicant | After uploading: checks size and file signature, marks it `UPLOADED` | SRS 2.5 |
| GET | `/sellers/me/documents/{id}/link` | applicant | Short-lived download link to one's own document | |
| GET | `/admin/sellers?status=` | admin | Review queue, paginated | |
| GET | `/admin/sellers/{id}` | admin | Detail with immutable history | FR-SELLER-03 |
| GET | `/admin/sellers/{id}/documents/{docId}/link` | admin | Short-lived link to review a KYC document | FR-SELLER-03 |
| POST | `/admin/sellers/{id}/decisions` | admin | `START_REVIEW`, `APPROVE`, `REJECT`, `SUSPEND`, `REINSTATE` + reason | FR-SELLER-02 |
| POST | `/admin/users/{id}/suspend` | admin | Suspend + revoke all sessions | FR-AUTH-06 |
| POST | `/admin/users/{id}/reactivate` | admin | Reactivate | |
| POST | `/admin/users/{id}/mfa/reset` | admin | Remove a user's MFA after an identity check, with a reason | |

Every `/admin/**` call needs an admin token whose `amr` claim includes `mfa`. An admin who logged in
with only a password gets `403 MFA_REQUIRED` and should enroll an authenticator app.

OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui.html`. Example calls: [`../../http/phase1.http`](../../http/phase1.http).

## Automatic verification (ADR 0007)

The seller applies with a GSTIN, the date on their PAN and a bank account, then connects DigiLocker.
A background worker runs four checks through the KYC provider:

| Check | Passes when |
|---|---|
| GSTIN | registered and Active |
| PAN (from the GSTIN) | valid; name and date match the GST legal name |
| Bank account (₹1 penny drop) | holder's name matches the legal, business or account name |
| Identity (DigiLocker Aadhaar) | name matches the legal name or account name |

If all four pass, the seller is approved automatically, with actor `00000000-0000-0000-0000-00000000a070`.
Otherwise the application goes to `UNDER_REVIEW`, and an admin sees each result under `verification` in
`GET /admin/sellers/{id}`. The account number and PAN date are stored encrypted.

**Mock provider test values** (`KYC_PROVIDER=mock`, the default). Every registry knows `KAI SHARMA`.

| Input | Result |
|---|---|
| GSTIN starting `99` | registration cancelled |
| GSTIN whose PAN part ends in `X` (e.g. `29ABCDE1234X1Z5`) | name on PAN doesn't match |
| Account ending `0000` | account doesn't exist |
| Account ending `1111` | held by `SOMEONE ELSE` |
| Account ending `5030` | bank network down: retried, then sent to an admin |

## Seller lifecycle

```
apply ──> PENDING_VERIFICATION ──START_REVIEW──> UNDER_REVIEW
                │                                   │
                ├──APPROVE / REJECT─────────────────┤
                v                                   v
            APPROVED <──REINSTATE── SUSPENDED    REJECTED ──re-apply──> PENDING_VERIFICATION
                └───────SUSPEND────────^
```

Every transition writes a `seller_status_history` row (actor, reason, correlation ID) and an event in
the same transaction. A database trigger rejects UPDATE and DELETE on the history table. Approval
grants the `SELLER` role; suspension removes it.

## Events (`identity-events`, key = aggregate id)

`UserRegistered`, `UserSuspended`, `UserReactivated`, `MfaEnabled`, `MfaDisabled`, `MfaReset`,
`SellerApplicationSubmitted`, `SellerDocumentUploaded`, `SellerVerificationCompleted`,
`SellerReviewStarted`, `SellerApproved`, `SellerRejected`, `SellerSuspended`, `SellerReinstated`.
Catalog will build its `approved_seller_projection` from the seller events; the audit service will
records all of them (see audit-service).

## Security notes

- Passwords: BCrypt, strength 12. Unknown emails still run a hash comparison so timing doesn't leak
  which emails are registered.
- Access token: RS256 JWT, 15 minutes, claims `sub`, `email`, `roles`, `amr`, `iss`; header `kid`
  is the key's RFC 7638 thumbprint. Only this service holds the private key.
- Refresh tokens remember whether the login passed MFA, so refreshed access tokens keep the same `amr`.
- MFA: TOTP (RFC 6238, SHA-1, 6 digits, 30 s, plus or minus one step). Secrets are AES-256-GCM encrypted
  and bound to the user ID. A used time step can't be reused. A login challenge lasts 5 minutes and
  dies after 5 wrong codes. Recovery codes are 80-bit, single-use and stored as SHA-256.
- MFA is required for admin actions, offered to everyone else. Setup and disable need the password.
- Refresh token: 256-bit random, stored as SHA-256, 14 days, rotated on every use. Reusing a rotated
  token revokes the whole chain.
- Lockout: 5 wrong passwords lock the account for 15 minutes. Per-IP limits are at the gateway.
- Suspended accounts are refused on every protected call, even with an unexpired access token.
- Signing-key rotation: `infra/keys/rotate-signing-key.sh` keeps the old public key trusted and
  published until it is removed, so nobody is logged out (ADR 0003).
- KYC files never pass through the API as JSON. The applicant gets a 15-minute link that only accepts
  the declared type and size, then confirms. The service reads the first bytes back and rejects files
  whose content doesn't match the declared type. Unconfirmed uploads expire after 24 hours (ADR 0005).

## Failure handling

| Failure | Behaviour |
|---|---|
| Kafka down | Requests still succeed. Events wait in `outbox_events` and are relayed when Kafka returns. After 10 failed attempts a row is parked as `FAILED` for investigation. |
| Several replicas | The relay locks rows with `FOR UPDATE SKIP LOCKED`, so each event is sent by one replica. Delivery is at-least-once; consumers dedupe on `eventId`. |
| Two admins decide at once | Optimistic locking on `sellers.version`; the loser gets `409 CONCURRENT_MODIFICATION`. |
| Two refreshes with one token | The token row is locked; the second call sees it rotated and the chain is revoked. |
| KYC provider down | The checks retry 5 times with growing gaps, then the application goes to an admin. Starting DigiLocker returns `503 KYC_UNAVAILABLE`. |
| Worker dies mid-check | Its 2-minute lease runs out and another replica picks the attempt up. |
| Admin decides while checks run | The admin's decision stands; the automatic result is only recorded. |
| Upload never confirmed | A scheduled job marks it `EXPIRED` and deletes any stored object. |
| Uploaded file is not what it claims | Confirm returns `422 INVALID_DOCUMENT`; the object is deleted. |
| Object storage down | Upload and link requests fail with 5xx; seller data is unaffected. |
| Bootstrap admin on several replicas | The unique email index lets one win; the others log and continue. |

## Configuration

| Variable | Default | |
|---|---|---|
| `JWT_PRIVATE_KEY_PATH` | `file:infra/keys/jwt-private.pem` | PKCS#8 RSA key, 2048+ bits |
| `MFA_ENCRYPTION_KEY` | none, required | base64 of 32 bytes |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | local `identity_db` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | empty | creates the first admin |
| `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL` | `15m`, `14d` | |
| `KYC_PROVIDER` | `mock` | `mock` or `sandbox` |
| `SANDBOX_API_KEY`, `SANDBOX_API_SECRET`, `SANDBOX_BASE_URL` | empty, empty, test API | keys from console.sandbox.co.in |
| `JWT_PREVIOUS_PUBLIC_KEYS` | empty | retired public keys still trusted after a rotation |
| `STORAGE_PROVIDER` | `local` | `local` (disk, signed links via the gateway) or `s3` |
| `STORAGE_LOCAL_DIR`, `STORAGE_PUBLIC_BASE_URL`, `STORAGE_SIGNING_KEY` | `./data/uploads`, gateway URL, random | local store |
| `S3_BUCKET`, `S3_REGION`, `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_PATH_STYLE` | | S3 store; endpoint for S3-compatible services |
