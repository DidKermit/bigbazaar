# api-gateway

Spring Cloud Gateway (WebFlux). Single entry point on port 8080. Stateless, so any number of replicas
can sit behind a load balancer.

## Responsibilities

- **Routing**: `/api/v1/auth/**`, `/users/**`, `/sellers/**`, `/admin/**` go to identity-service.
  Later phases add their routes in `application.yml`.
- **Authentication boundary**: requests under `/api/**` need a valid JWT (signature, expiry, issuer)
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

## Configuration

`JWT_SECRET` (required, same as identity), `JWT_ISSUER`, `REDIS_HOST`, `REDIS_PORT`,
`IDENTITY_SERVICE_URL`, `CORS_ALLOWED_ORIGINS`.
