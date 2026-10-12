# api-gateway

Spring Cloud Gateway (WebFlux). Single entry point on port 8080. Stateless, so any number of replicas
can sit behind a load balancer.

## Responsibilities

- **Routing**: `/api/v1/auth/**`, `/users/**`, `/sellers/**`, `/admin/**` and the signed
  `/api/v1/storage/objects/**` links go to identity-service; `/api/v1/admin/audit-events/**` goes to
  audit-service (listed first so it wins over identity's `/admin/**`).
  Later phases add their routes in `application.yml`.
- **Authentication boundary**: requests under `/api/**` need a valid RS256 JWT, checked against
  identity's public keys from `/.well-known/jwks.json` (fetched lazily, cached, refetched on an
  unknown `kid`). Signature, expiry and issuer are checked
  except `POST /api/v1/auth/**`. Anything outside `/api/**` and health is denied. Services re-check
  the token and do role and ownership checks themselves.
- **Rate limiting** (Redis token bucket): auth endpoints 5 req/s per IP with bursts of 10;
  everything else 50 req/s per user.
- **Correlation IDs**: keeps a valid incoming `X-Correlation-Id` or creates one, forwards it, and
  returns it on every response, including 401s.
- **CORS**: origins from `CORS_ALLOWED_ORIGINS`.
- Strips `Cookie` headers before forwarding; the API is token-based.

## Failure handling

| Failure | Behaviour |
|---|---|
| Redis down | The rate limiter fails open and logs an error, so traffic keeps flowing unthrottled. |
| Identity down | Routed calls return 5xx; public health stays up. |
| Bad or expired token | 401 with the standard error body and correlation ID. |
| Identity down when keys aren't cached yet | Authenticated calls fail until the JWKS can be fetched. |

## Configuration

`JWT_JWK_SET_URI` (identity's JWKS URL), `JWT_ISSUER`, `REDIS_HOST`, `REDIS_PORT`,
`IDENTITY_SERVICE_URL`, `AUDIT_SERVICE_URL`, `CORS_ALLOWED_ORIGINS`.
