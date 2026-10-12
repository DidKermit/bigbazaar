# BigBazaar

AI-powered, event-driven, multi-vendor marketplace. Built incrementally as coarse-grained Spring Boot
microservices that talk over REST for commands and queries and Kafka for business facts. Each service
owns its own PostgreSQL database. Requirements live in the SRS (v1.0, 17 July 2026).

## Status

| Phase | Weeks | Scope | State |
|---|---|---|---|
| 1. Platform + Identity + Gateway | 1-2 | Auth, roles, seller onboarding, gateway routing | **In progress** |
| 2. Catalog + Commerce | 3-5 | Products, inventory, cart, orders, seller orders | Not started |
| 3. Payment + Notification + Trust | 6-8 | Payment Saga, notifications, verified reviews, trust scores | Not started |
| 4. Search + AI + Deployment | 9-12 | OpenSearch, recommendations, AI assistant, frontend, monitoring | Not started |

## Repository layout

```
bigbazaar/
├── pom.xml                      Maven parent: Java 21, Spring Boot 4.0, Spring Cloud 2025.1
├── libs/common/                 Tiny shared contracts: EventEnvelope, ApiError, topic + header names
├── services/
│   ├── identity-service/        Users, roles, JWT + refresh tokens, seller onboarding  (port 8081)
│   ├── api-gateway/             Routing, CORS, Redis rate limits, correlation IDs, JWT edge check (8080)
│   └── README.md                Planned services and their ports
├── infra/postgres/              Creates one database + login per service for local dev
├── docs/adr/                    Architecture Decision Records
├── http/                        Example requests (IntelliJ / VS Code REST Client)
└── docker-compose.yml           PostgreSQL, Redis, Kafka, (Kafka UI, OpenSearch) and the services
```

`libs/common` deliberately holds no JPA entities or Spring beans (SRS 6.5). Services share contracts,
not code.

## Run it

Prerequisites: JDK 21, Docker.

```bash
cp .env.example .env
infra/keys/generate-dev-keys.sh   # RSA signing key + prints MFA_ENCRYPTION_KEY for .env
docker compose --profile app up -d --build
```

Everything is reached through the gateway on `http://localhost:8080`. Identity's Swagger UI is at
`http://localhost:8081/swagger-ui.html`. Try the flow in [`http/phase1.http`](http/phase1.http):
register, log in, apply as a seller, enroll the bootstrap admin's authenticator app, approve.

To run a service from your IDE instead, start only the infrastructure (`docker compose up -d`) and
run identity-service from the repository root with `MFA_ENCRYPTION_KEY` set and
`KAFKA_BOOTSTRAP_SERVERS=localhost:29092` (it reads `infra/keys/jwt-private.pem` by default).

## Build and test

```bash
mvn verify
```

Integration tests use Testcontainers (real PostgreSQL and Kafka), so Docker must be running.

## Conventions (SRS 8.2, 9.1, 12.1)

- UUID ids, `TIMESTAMPTZ` / `Instant`, `NUMERIC(12,2)` / `BigDecimal`, enums stored as strings.
- Flyway owns the schema; Hibernate runs with `ddl-auto=validate`.
- Soft delete via `deleted_at`, `deleted_by`, `delete_reason`; transactional history is append-only.
- Every event is an `EventEnvelope` written to a transactional outbox, then relayed to Kafka.
- Every error is `{success, code, message, correlationId, timestamp, fieldErrors}`.
- Every request carries `X-Correlation-Id`; it appears in logs, error bodies and events.
- Access tokens are RS256. Only identity holds the private key; everything else verifies with
  identity's public JWKS. Admin endpoints need a token from a login that passed MFA (`amr` has `mfa`).
- No service reads another service's database. Cross-service data arrives as event projections.

## Architecture decisions

- [ADR 0001: Coarse-grained microservices](docs/adr/0001-coarse-grained-microservices.md)
- [ADR 0002: Transactional outbox for events](docs/adr/0002-transactional-outbox.md)
- [ADR 0003: RS256 tokens with rotating refresh tokens](docs/adr/0003-jwt-and-refresh-tokens.md)
- [ADR 0004: Multi-factor authentication](docs/adr/0004-multi-factor-authentication.md)
