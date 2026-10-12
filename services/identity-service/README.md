# identity-service

Owns users, roles, addresses, refresh tokens and seller onboarding. Database: `identity_db`.
Publishes to the `identity-events` topic.

## API (`/api/v1`)

| Method | Path | Who | Purpose | SRS |
|---|---|---|---|---|
| POST | `/auth/register` | public | Create a customer account | FR-AUTH-01, 02 |
| POST | `/auth/login` | public | Email + password to access and refresh tokens | FR-AUTH-03 |
| POST | `/auth/refresh` | public | Rotate a refresh token | FR-AUTH-04 |
| POST | `/auth/logout` | public | Revoke a refresh token's chain | FR-AUTH-04 |
| GET | `/users/me` | any user | Profile and roles | FR-AUTH-05 |
| GET/POST/DELETE | `/users/me/addresses` | any user | Address book (soft delete) | |
| POST | `/sellers/applications` | customer | Apply, or re-apply after rejection | FR-SELLER-01 |
| GET | `/sellers/me` | applicant | Application, documents, status history | FR-SELLER-05 |
| POST | `/sellers/me/documents` | applicant | Register an uploaded KYC document | |
| GET | `/admin/sellers?status=` | admin | Review queue, paginated | |
| GET | `/admin/sellers/{id}` | admin | Detail with immutable history | FR-SELLER-03 |
| POST | `/admin/sellers/{id}/decisions` | admin | `START_REVIEW`, `APPROVE`, `REJECT`, `SUSPEND`, `REINSTATE` + reason | FR-SELLER-02 |
| POST | `/admin/users/{id}/suspend` | admin | Suspend + revoke all sessions | FR-AUTH-06 |
| POST | `/admin/users/{id}/reactivate` | admin | Reactivate | |

OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui.html`. Example calls: [`../../http/phase1.http`](../../http/phase1.http).

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

`UserRegistered`, `UserSuspended`, `UserReactivated`, `SellerApplicationSubmitted`,
`SellerReviewStarted`, `SellerApproved`, `SellerRejected`, `SellerSuspended`, `SellerReinstated`.
Catalog will build its `approved_seller_projection` from the seller events; the audit service will
record all of them.

## Security notes

- Passwords: BCrypt, strength 12. Unknown emails still run a hash comparison so timing doesn't leak
  which emails are registered.
- Access token: HS256 JWT, 15 minutes, claims `sub`, `email`, `roles`, `iss`.
- Refresh token: 256-bit random, stored as SHA-256, 14 days, rotated on every use. Reusing a rotated
  token revokes the whole chain.
- Lockout: 5 wrong passwords lock the account for 15 minutes. Per-IP limits are at the gateway.
- Suspended accounts are refused on every protected call, even with an unexpired access token.

## Failure handling

| Failure | Behaviour |
|---|---|
| Kafka down | Requests still succeed. Events wait in `outbox_events` and are relayed when Kafka returns. After 10 failed attempts a row is parked as `FAILED` for investigation. |
| Several replicas | The relay locks rows with `FOR UPDATE SKIP LOCKED`, so each event is sent by one replica. Delivery is at-least-once; consumers dedupe on `eventId`. |
| Two admins decide at once | Optimistic locking on `sellers.version`; the loser gets `409 CONCURRENT_MODIFICATION`. |
| Two refreshes with one token | The token row is locked; the second call sees it rotated and the chain is revoked. |
| Bootstrap admin on several replicas | The unique email index lets one win; the others log and continue. |

## Configuration

| Variable | Default | |
|---|---|---|
| `JWT_SECRET` | none, required | 32+ bytes, shared with the gateway |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | local `identity_db` | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | empty | creates the first admin |
| `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL` | `15m`, `14d` | |
