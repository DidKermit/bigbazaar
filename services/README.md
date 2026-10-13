# Services

| Service | Phase | Port | Database | Status |
|---|---|---|---|---|
| api-gateway | 1 | 8080 | none (Redis for rate limits) | Built |
| identity-service | 1 | 8081 | identity_db | Built |
| audit-service | 1 | 8091 | platform_db | Built |
| catalog-service | 2 | 8082 | catalog_db | Planned |
| commerce-service | 2 | 8083 | commerce_db | Planned |
| payment-service | 3 | 8084 | payment_db | Planned |
| notification-service | 3 | 8085 | notification_db | Planned |
| trust-service | 3 | 8086 | trust_db | Planned |
| search-service | 4 | 8087 | OpenSearch | Planned |
| recommendation-service | 4 | 8088 | recommendation_db | Planned |
| ai-service (Python FastAPI) | 4 | 8090 | optional ai logs | Planned |
| fulfillment-service | 3 | 8089 | fulfillment_db | Planned (SRS v1.1) |
| insights-service | 4 | 8092 | insights_db | Planned (SRS v1.1) |

The local PostgreSQL init script already creates every v1.0 database and its login, so a new service only
needs its module, Flyway migrations, a gateway route and a compose entry. `fulfillment_db` and `insights_db`
(added in SRS v1.1) get added to the init script when those services are built.

## Definition of done for a service (SRS 7.1)

1. Flyway migrations run on an empty database.
2. Unit and integration tests pass.
3. Security and ownership tests pass where applicable.
4. Kafka producer or consumer tests pass where applicable.
5. Docker image builds and the health endpoint works.
6. OpenAPI and example requests are updated.
7. The service README explains responsibilities and failure handling.
