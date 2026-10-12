# ADR 0001: Coarse-grained microservices, built incrementally

- Status: Accepted
- Date: 2026-10-12

## Context

BigBazaar must show realistic microservice design but ship in about twelve weeks with a small team.
Splitting into twenty fine-grained services would turn ordinary transactions (reserve stock and
create an order) into distributed ones before there is a reason to.

## Decision

Nine business services plus a gateway and a Python AI service, split by business responsibility:
Identity, Catalog, Commerce, Payment, Notification, Trust, Search, Recommendation, AI. Tightly
coupled transactional logic stays together; inventory, cart and orders all live in Commerce at first.

Services use REST for immediate commands and queries and Kafka for facts other services react to.
Each owns its database; others hold only logical IDs or event-built projections. Services are built
in the order of the SRS timeline, each reaching the completion gate (SRS 7.1) before the next starts.

One Maven multi-module repository holds every Java service so shared build config, CI and the
`libs/common` contracts stay in step. Each module still builds, tests and ships as its own image.

## Consequences

- Fewer network hops and no distributed transaction for checkout in the first release.
- Commerce is the likeliest future split (inventory vs orders) once load or team size calls for it.
- A monorepo makes cross-service refactors easy, so the "no shared entities" rule needs review discipline.
