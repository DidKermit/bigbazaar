# ADR 0008: SRS v1.1 scope: fulfillment, settlement, insights and the admin role

- Status: Accepted
- Date: 2026-10-13
- Changes: SRS v1.0 → v1.1 (`docs/srs/SRS-v1.1.md`)

## Context

A review of SRS v1.0 against how Indian marketplaces work found that it covers buying well but stops
at payment: nothing ships an order or sends `OrderDelivered`, returns are a one-line Should, sellers
are never paid, and there are no tax invoices. `ProductStatus.PENDING_APPROVAL` implied an admin
approves every listing, the same manual step ADR 0007 removed for sellers. Dashboards were bullet
lists with no data source. kai also shared a UI mockup with three areas (shop, seller dashboard,
platform insights) and set the admin's role.

## Decision

- **Fulfillment service** (new, `fulfillment_db`): serviceability, shipments through a courier
  adapter (mock, then sandbox), tracking webhooks, failed delivery and RTO, returns and reverse
  pickup. It owns `OrderDelivered`. Kept apart from Commerce so courier webhooks and outages don't
  sit next to orders and stock.
- **Settlement inside Payment**: a double-entry ledger, commission, holding money until the return
  window closes, payouts on a cycle, GST invoices, TCS/TDS. A module, not a new service, following
  ADR 0001's coarse-grained rule; it can be split out later.
- **Insights service** (new, `insights_db`): consumes all events into daily totals that back the
  seller and admin dashboards and reports. Read-only to every role. Same consumer pattern as the
  audit service (ADR 0006); dashboards never query other services' databases.
- **Automatic listing checks** in Catalog, with admin deciding only doubtful listings.
- **Admin role**: views all insights and generates reports, cannot edit insights, reports or
  history. Admin also decides exceptions (unresolved KYC, flagged listings, suspensions, disputes,
  flagged reviews, support tickets) with MFA, a reason and an audit record. The SUPPORT role is
  removed.
- **Frontend**: one Angular app with three lazy-loaded, role-guarded areas.
- **Capacity**: targets measured with k6 against seeded data on stated hardware, plus an injectable
  clock for time-based flows.
- Each seller keeps owning their own products; shared product pages with many offers stay future.
- Crypto payments are future only, pending a legal review.

## Consequences

- Two more services to build and run (13 including gateway and audit). Phase 3 carries most of the
  new work; whether to extend past 12 weeks is decided as the project goes.
- Reviews and trust scores now have a real trigger (`OrderDelivered` from Fulfillment).
- Seller payout timing depends on return windows, so returns and settlement are built together.
- A powerful admin is acceptable because every admin action needs MFA and lands in the
  append-only audit log.
