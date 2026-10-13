"""The words around the diagrams: what each one shows, how to read it, and the key ideas."""

INTRO = """
This document draws BigBazaar from five angles. Each angle answers a different question, and together
they describe the whole system before we build Phase 2.

- **System architecture**: what are the big boxes, and how do they talk?
- **Components**: what is inside one box?
- **Data flow**: where does information come from and where does it end up?
- **Database schema (ERD)**: what tables does each service keep, and how are they linked?
- **Request flows**: step by step, what happens when someone logs in, buys, ships or returns?

Every diagram is written in **Mermaid**, a text format for diagrams. The text lives in
`docs/diagrams/mermaid/`, so a diagram changes in a pull request like code does, and GitHub draws it
in the browser. The full source of every diagram is printed in the appendix.

**What is built and what is planned.** Phase 1 built the gateway, Identity and Audit. Everything else
is designed here from SRS v1.1 and will be built in later phases. In the schema tables a green
**Built** badge means the table exists in a Flyway migration today; **Planned** means it is designed
from SRS 8.4; an amber badge marks the few tables these diagrams add that SRS 8.4 does not name,
each with a note saying why it is needed.
"""

CONCEPTS = """
These ideas explain most of the lines you will see.

- **Microservice with its own database.** Each service (Identity, Catalog, Commerce and so on) owns
its tables. No other service reads them or points a foreign key at them (SRS 3.5). That keeps each
service free to change its own tables without breaking others.
- **Logical id instead of a foreign key.** When Commerce stores a `seller_id`, it is just a UUID copied
from Identity, not a database link. The ERDs show these as "logical id"; the overview page shows which
database borrows ids from which.
- **Projection.** A service that needs another service's data keeps its own read-only copy, built from
events. Catalog's `verified_seller_projection` is the list of approved sellers, rebuilt from
`SellerApproved` and `SellerSuspended` events. This avoids calling Identity on every product save.
- **Transactional outbox.** A service saves its change and an "event to send" row in the same
database transaction, and a relay sends the row to Kafka afterwards. So an event is published if and
only if the change was saved (ADR 0002).
- **Idempotent consumer.** Kafka may deliver an event twice. Each consumer remembers the event ids it
has handled in `processed_events` and skips repeats, so doing the work twice can never double a
refund or a stock change.
"""

SECTIONS = [
    {
        "id": "architecture", "title": "System architecture",
        "intro": """
The big picture: which pieces exist and how they connect. Two kinds of arrows matter. **Solid REST
arrows** are a question that needs an answer now ("log me in", "show this product"). **Kafka
arrows** are announcements of facts that already happened ("order created"), which any interested
service can react to later.
""",
        "diagrams": [
            {
                "id": "arch-system", "file": "01-system-architecture",
                "title": "Target system architecture",
                "srs": "3.1 Target architecture, 3.4 Communication rules, 7 Microservice requirements, 14 External interfaces",
                "read": """
Read top to bottom. The one Angular app sends every request through the load balancer to the API
gateway, which checks the token, applies rate limits and routes to one of eleven Java services. Each
service writes to its own PostgreSQL database and announces facts on Kafka through its outbox. The
bottom row is everything outside our control, always reached through an adapter so a mock can stand
in during development.
""",
                "concepts": """
- **Why a gateway?** One front door means the browser knows one address, and token checks, rate
limits, CORS and correlation ids are done once instead of in every service.
- **Why the AI service is separate and in Python.** Only Recommendation calls it, and Python has the
best AI tooling. Keeping it behind Recommendation means the AI can never invent a product: it only
ranks and explains real catalog candidates (FR-AI-02).
- **Webhooks** are calls the payment provider and courier make to us. They enter through the gateway
like any other request and are verified and deduplicated by the owning service.
""",
            },
            {
                "id": "arch-deploy", "file": "02-deployment",
                "title": "Deployment and load balancing",
                "srs": "15 Deployment and operations, 15.2 Load balancing, 6.3 Availability, 6.6 Observability",
                "read": """
How it runs in production. Every service is stateless, so two or more copies run behind the load
balancer and any copy can serve any request. State lives only in the data stores on the right.
""",
                "concepts": """
- **Stateless** means a service keeps nothing in memory between requests. That is what lets us add
copies under load and lose one without losing data.
- **.DLT topics** (dead-letter topics) hold events a consumer failed to process after retries, so one
bad event cannot block the rest and can be inspected later.
""",
            },
            {
                "id": "arch-network", "file": "08-network",
                "title": "Network diagram (production)",
                "srs": "12 Security requirements, 15 Deployment, 15.2 Load balancing, 6.3 Availability",
                "read": """
Read left to right, from the internet inward. Each box is a network zone and each arrow is a
connection that a firewall rule allows, labelled with its port. Anything without an arrow is blocked.
Only the load balancer has a public address. Services sit in a private subnet, databases in a deeper
one with no internet route at all, and calls out to providers leave through one NAT gateway. Every
zone is copied across two availability zones (separate data centres). Ports for services not built
yet are proposed; the built ones (gateway 8080, identity 8081, audit 8091) match the code.
""",
                            },
            {
                "id": "arch-cloud", "file": "@text",
                "title": "Recommended cloud setup and firewall rules",
                "srs": "12 Security requirements, 14 External interfaces, 15 Deployment",
                "read": """
**Recommendation: AWS, Mumbai region (ap-south-1).** The storage adapter already defaults to S3 in
ap-south-1, Indian payment and KYC rules favour keeping customer data in India, and every box in the
network diagram has a managed AWS service, so the team runs code rather than servers. The diagram
keeps generic names so it stays true if another cloud is chosen (Azure Central India and GCP
asia-south1 have the same pieces, but no managed Kafka on Azure).

Three ideas behind the network diagram:
- **Subnet** is a slice of the private network (written as a CIDR range such as 10.0.16.0/20). Putting
each tier in its own subnet lets firewall rules say "only the app tier may reach the database".
- **Defence in depth**: an attacker who gets past the load balancer still cannot reach a database
directly, and a stolen database password is useless from outside the network.
- **NAT gateway** lets private machines start connections to the internet (KYC, payments) while
nothing on the internet can start a connection to them.
""",
                "tables": [
                    (["Diagram box", "AWS service", "Why"], [
                        ("Private network, subnets, firewall rules", "VPC over 2 availability zones, security groups", "Rules reference groups, not IPs, so scaling never breaks them."),
                        ("DNS", "Route 53", "Health-checked records."),
                        ("CDN + WAF", "CloudFront + AWS WAF", "Serves the Angular build from S3 near the user."),
                        ("Load balancer", "Application Load Balancer + AWS WAF, ACM certificate", "HTTPS ends here; spreads load over gateway replicas."),
                        ("Gateway, services, AI service", "ECS on Fargate, images in ECR", "Containers without managing servers; two or more tasks per service."),
                        ("PostgreSQL", "RDS PostgreSQL Multi-AZ", "Automatic standby and backups; one login per service database."),
                        ("Kafka", "Amazon MSK (3 brokers, TLS)", "Managed Kafka with the same client code."),
                        ("Redis", "ElastiCache for Redis, TLS", "Rate limits and cache."),
                        ("OpenSearch", "Amazon OpenSearch Service (3 nodes)", "Same engine the code targets."),
                        ("Object storage + private endpoint", "S3 + VPC gateway endpoint", "File traffic never leaves AWS's network."),
                        ("NAT gateway", "NAT gateway in each public subnet", "Outbound calls to providers only."),
                        ("Monitoring", "Managed Prometheus + Grafana, CloudWatch logs", "Metrics, logs and traces with the correlation id."),
                        ("VPN endpoint", "AWS Client VPN (or SSM Session Manager)", "Admins reach ops tools without any public SSH."),
                    ]),
                    (["From", "To", "Port", "Allowed because"], [
                        ("Internet", "Load balancer", "443", "The only public entry: browsers and signed webhooks."),
                        ("Load balancer", "API gateway", "8080", "Gateway checks tokens and routes."),
                        ("API gateway", "Services", "8081 to 8092", "REST calls the gateway routes."),
                        ("API gateway, services", "Redis", "6379", "Rate limits and cache."),
                        ("Recommendation", "AI service", "8000", "Only Recommendation calls the AI."),
                        ("Each service", "PostgreSQL", "5432", "Each with its own login to its own database."),
                        ("Services", "Kafka", "9093", "Outbox relays publish, listeners consume."),
                        ("Search", "OpenSearch", "9200", "Indexing and queries."),
                        ("Services", "Object storage endpoint", "443", "Documents, images, invoices, reports."),
                        ("Services, AI service", "NAT gateway → providers", "443", "KYC, payment, courier, SMS, email, LLM."),
                        ("Monitoring", "Services", "actuator port", "Metrics scraping."),
                        ("Anything else", "Anything", "none", "Denied by default."),
                    ]),
                ],
            },
        ],
    },
    {
        "id": "components", "title": "Component diagrams",
        "intro": """
A component diagram opens one box from the architecture and shows the parts inside it. We draw the
Identity service, because it is built and every later Java service will follow the same layers, and
the Angular web app, which kai chose to build as one app with three areas.
""",
        "diagrams": [
            {
                "id": "comp-identity", "file": "03-component-identity",
                "title": "Identity service internals (the template for every Java service)",
                "srs": "7 Microservice requirements, 9.2 Outbox, 12 Security; code under services/identity-service",
                "read": """
Requests come in at the top through the security filter, reach a controller, which calls a service
class holding the business rules, which saves through repositories. Anything that talks to the
outside world (KYC provider, file storage, Kafka) sits behind an adapter at the bottom. Two scheduled
jobs run on their own: the verification worker and the outbox relay.
""",
                "concepts": """
- **Layers.** Controller (HTTP in and out) → service (rules) → repository (database). Rules never sit in
controllers, so they can be tested without HTTP.
- **Adapter.** An interface such as `KycProvider` with a mock and a real implementation, picked by
configuration. Catalog, Payment and Fulfillment will reuse this for images, payments and couriers.
- **`libs/common`** holds what every service must agree on: the event envelope, topic names, the error
response and header names.
""",
            },
            {
                "id": "comp-web", "file": "04-component-web-app",
                "title": "Angular web app",
                "srs": "13 User interface requirements (13.1 Shop, 13.2 Seller center, 13.3 Admin insights)",
                "read": """
One Angular build with three lazily loaded areas: `/shop` for everyone, `/seller` for sellers and
`/admin` for admins. The core layer is loaded once and shared: it holds the login state, attaches the
token to every call and blocks routes the user may not open.
""",
                "concepts": """
- **Lazy loading** means a shopper's browser never downloads the seller or admin code.
- **Route guards are for convenience, not security.** The real check is always on the server; the
guard only stops the app from showing a page the server would refuse.
- **Admin has no order or listing controls.** Admin sees insights and decides only exceptions
(SRS 4.1), so the admin area is dashboards plus an exceptions queue.
""",
            },
        ],
    },
    {
        "id": "dataflow", "title": "Data-flow diagrams",
        "intro": """
A data-flow diagram (DFD) ignores servers and code and follows the information. Rounded boxes are
people or outside systems, rectangles are processes that change data, cylinders are data stores and
arrows are labelled with the data that moves. Level 0 shows the whole system as one process; level 1
opens it into its main processes.
""",
        "diagrams": [
            {
                "id": "dfd-0", "file": "05-dfd-context",
                "title": "Level 0: context diagram",
                "srs": "2.1 Product perspective, 4 User classes and actors, 14 External interfaces",
                "read": """
Everything BigBazaar receives and sends, and from whom. Three human roles (customer, seller, admin;
there is no support role in v1.1) and five outside providers.
""",
            },
            {
                "id": "dfd-1", "file": "06-dfd-level1",
                "title": "Level 1: main processes and data stores",
                "srs": "3.5 Data ownership, 5 Functional requirements, 9 Event-driven architecture",
                "read": """
Each numbered process is roughly one service and owns exactly one data store (D1 to D9). The small
text in each process says who feeds it, from the context diagram. Processes never reach into each
other's stores. Instead, facts leave each store through its outbox as events, and other processes read
them back to build their own copies. Insights and Audit read every event, which is why
dashboards never query the other databases.
""",
            },
            {
                "id": "dfd-events", "file": "07-event-flow",
                "title": "Event flow: who publishes and who listens",
                "srs": "9.3 Event catalog, 9.4 Consumer requirements",
                "read": """
Each service publishes to exactly one topic named after it. Read a consumer box to see what it builds
from the events it receives. Insights and Audit subscribe to every topic.
""",
                "concepts": """
- **One topic per producer** keeps ownership obvious: only Commerce writes `commerce-events`.
- **Message key = entity id** (for example the order id), so all events about one order arrive in
order on the same partition.
""",
            },
        ],
    },
    {
        "id": "schema", "title": "Database schema (ERD)",
        "intro": """
An entity-relationship diagram (ERD) shows tables as boxes and foreign keys as lines. As agreed, the
diagrams show only the **main columns** (keys, status and the fields that explain the table) so each
page stays readable. **Every column**, with type, nullability, keys and notes, is in the column
tables at the end of this part.

How to read the lines (crow's foot notation): `||` means exactly one, `|o` zero or one, `o{` zero or
many. So `users ||--o{ addresses` reads "one user has zero or many addresses". Lines exist only inside
one database. A column marked "logical id" holds an id owned by another service, with no foreign key.

Conventions from SRS 8.2 apply everywhere: UUID ids (BIGINT for high-volume append-only logs), money
as `numeric(12,2)`, times as `timestamptz`, enums stored as strings, and `deleted_at` soft delete on
the tables that allow it. History tables (status history, stock movements, ledger entries, audit
events) are append-only: a database trigger refuses updates and deletes.
""",
        "diagrams": [
            {
                "id": "erd-overview", "file": "20-erd-00-overview",
                "title": "Overview: databases and the ids they borrow",
                "srs": "3.5 Data ownership, 8.1 Database strategy",
                "read": """
One cylinder per database (green ones exist today). A dotted arrow from A to B means A stores ids of
B's records, as plain values. For example Commerce stores `users` and `sellers` ids from Identity.
""",
            },
            {"id": "erd-identity", "file": "20-erd-01-identity-db", "title": "identity_db (Identity)",
             "srs": "8.4; migrations V1 to V4",
             "read": """
Built in Phase 1. Users hold roles through `user_roles`. MFA adds `user_mfa`, recovery codes and
login challenges. A user can apply to become a seller; each application attempt is a
`seller_verifications` row with one `seller_verification_checks` row per KYC check.
`phone_verifications` is the only planned table here (FR-AUTH-09).
"""},
            {"id": "erd-catalog", "file": "20-erd-02-catalog-db", "title": "catalog_db (Catalog)",
             "srs": "8.4, 5.3 Catalog",
             "read": """
A product belongs to a category and optionally a brand, and has one or more variants (the SKUs people
actually buy). Categories carry the return window and commission rate. Price changes append to
`price_history`; listing checks record why a product went live, was rejected or went to an admin.
"""},
            {"id": "erd-commerce", "file": "20-erd-03-commerce-db", "title": "commerce_db (Commerce)",
             "srs": "8.4, 8.6 Inventory, 8.7 Orders",
             "read": """
Inventory and orders live together so that reserving stock and creating the order happen in one
transaction. An order splits into one `seller_orders` row per seller; `order_items` copy every price,
tax and product detail at purchase time so later edits never change an old order.
"""},
            {"id": "erd-payment", "file": "20-erd-04-payment-db", "title": "payment_db (Payment and settlement)",
             "srs": "8.4, 8.8 Ledger, 5.8 to 5.9",
             "read": """
Two halves. Payments: `payments`, their attempts, raw webhooks and refunds. Money bookkeeping: the
double-entry ledger, settlements per seller per cycle, payouts and GST invoices. The ledger lives
here (not in its own service) because a payment and its ledger entries must be saved together.
"""},
            {"id": "erd-fulfillment", "file": "20-erd-05-fulfillment-db", "title": "fulfillment_db (Fulfillment)",
             "srs": "8.4, 5.6 Shipping, 5.7 Returns",
             "read": """
One shipment per seller order with its tracking timeline, built from deduplicated courier webhooks.
Returns are requested per order item and each gets one reverse pickup.
"""},
            {"id": "erd-notification", "file": "20-erd-06-notification-db", "title": "notification_db (Notification)",
             "srs": "8.4, 5.12",
             "read": "Each notification is one message on one channel, with every send attempt logged for retries."},
            {"id": "erd-trust", "file": "20-erd-07-trust-db", "title": "trust_db (Trust)",
             "srs": "8.4, 5.10 Reviews and trust",
             "read": """
A review can only exist for a delivered order item, and only one per item, which the one-to-one line
from `delivered_item_projection` to `reviews` shows. Scores keep their reasons in `components` and
every change in `score_history`.
"""},
            {"id": "erd-recommendation", "file": "20-erd-08-recommendation-db", "title": "recommendation_db (Recommendation)",
             "srs": "8.4, 5.11, 11 AI and recommendation",
             "read": """
Activity goes in, ranked lists come out. Every list is logged with the algorithm version and each
item's reasons, and every AI call is logged with its tokens, cost and time (FR-AI-04).
"""},
            {"id": "erd-insights", "file": "20-erd-09-insights-db", "title": "insights_db (Insights)",
             "srs": "8.4, 5.13 Insights and reports",
             "read": """
Pre-added daily totals per platform, seller and product, so dashboards are fast and never touch other
databases. Nothing here is edited by people; reports are stored snapshots.
"""},
            {"id": "erd-platform", "file": "20-erd-10-platform-db", "title": "platform_db (Audit, support tickets)",
             "srs": "8.4, FR-AUD-01 to 03, FR-SUP-01",
             "read": "`audit_events` is built and append-only. Support tickets and their messages are planned."},
            {"id": "erd-search", "file": "20-erd-11-search-index", "title": "Search index (OpenSearch)",
             "srs": "5.11, FR-SRCH-01 to 03",
             "read": "Search keeps no SQL tables. This is the shape of one document in the products index."},
            {"id": "columns", "file": "@columns", "title": "Every table and every column",
             "read": """
The full schema, database by database. **Bold** column names (● in the ERD column) are the ones drawn
in the diagrams. Null "yes" means the column may be empty. FK is a real foreign key inside the same
database; "ref" is a logical id owned by another service. Types are PostgreSQL types.
"""},
        ],
    },
    {
        "id": "requests", "title": "Request-flow diagrams",
        "intro": """
Sequence diagrams show one scenario in time order, top to bottom. Each column is a participant.
Solid arrows are direct calls that wait for an answer; dashed arrows with an open head are Kafka
events that nobody waits for. `alt` boxes show the different outcomes, `loop` a repeated step.
""",
        "diagrams": [
            {"id": "rf-gateway", "file": "30-request-through-gateway", "title": "Any request through the gateway",
             "srs": "12 Security, 12.1 Standard error response, 6.6 Observability",
             "read": "The path every API call takes, and where 429 and 401 errors come from.",
             "concepts": """
- **Correlation id**: one id attached at the gateway and passed to every service and event, so the
logs of one user action can be found together across services.
- **JWKS**: the gateway checks tokens with Identity's public keys, so it never needs the private key.
"""},
            {"id": "rf-login", "file": "31-login-with-mfa", "title": "Login with MFA", "srs": "5.1, ADR 0003, ADR 0004",
             "read": "Built in Phase 1. Admins always get the second step; other users only if they turned MFA on."},
            {"id": "rf-checkout", "file": "32-checkout-and-payment", "title": "Checkout and payment saga",
             "srs": "10.1, 5.4, 5.5, 5.8",
             "read": """
The most important flow. Commerce creates the order and reserves stock in one transaction; Payment
reacts to the event, and Commerce reacts to the payment result.
""",
             "concepts": """
- **Saga**: a business process split across services, each step local and committed, with a
compensating step if something later fails. Here the compensation for a failed payment is releasing
the reserved stock. There is no distributed transaction.
- **Idempotency key**: the browser sends the same key if it retries checkout, and gets the same order
back instead of a second one.
"""},
            {"id": "rf-seller", "file": "33-seller-verification", "title": "Seller verification",
             "srs": "10.2, FR-SELLER-02 and 07, ADR 0007",
             "read": "Built in Phase 1. Clean applications are approved by the system; the admin sees only exceptions."},
            {"id": "rf-product", "file": "34-product-publication", "title": "Product publication", "srs": "10.3, FR-CAT-10",
             "read": "Automatic checks decide most listings; only doubtful ones reach an admin."},
            {"id": "rf-ship", "file": "35-shipping-and-delivery", "title": "Shipping and delivery", "srs": "10.4, 5.6",
             "read": "One `OrderDelivered` event fans out to four services, each doing its own part."},
            {"id": "rf-return", "file": "36-return-and-refund", "title": "Return and refund", "srs": "10.5, 5.7",
             "read": "Refunds are new reversing ledger entries; nothing already recorded is edited."},
            {"id": "rf-settle", "file": "37-settlement-and-payout", "title": "Settlement and payout", "srs": "10.6, 5.9, 8.8",
             "read": "Money is held until the return window closes, then settled per seller on a cycle.",
             "concepts": """
- **Double-entry ledger**: every money movement is written as equal debits and credits across
accounts, so the books always balance and any balance can be recomputed by adding entries.
"""},
            {"id": "rf-review", "file": "38-review-and-trust", "title": "Review and trust", "srs": "10.7, 5.10",
             "read": "Only verified buyers can review, and suspicious reviews wait for an admin."},
            {"id": "rf-outbox", "file": "39-outbox-and-idempotent-consumer", "title": "Outbox and idempotent consumer",
             "srs": "9.2, 9.4, ADR 0002",
             "read": "The mechanism under every dashed arrow above. Built in Phase 1 for Identity and Audit."},
        ],
    },
]

CHOICES = [
    ("Diagram format", "Mermaid text files", "PlantUML; draw.io",
     "Lives in git, reviewed in PRs, drawn by GitHub. PlantUML needs Java to render; draw.io drifts from the code."),
    ("ERD detail", "Main columns in diagrams, every column in tables", "Every column in the diagrams",
     "Full-column ERDs become wall-sized and unreadable on a page (kai's call)."),
    ("Schema source", "One Python file (`tools/schema.py`) generates ERDs and tables", "Hand-written ERDs",
     "The diagram and the column tables cannot disagree."),
    ("Cross-service links", "Logical ids, drawn on the overview only", "Foreign keys across databases",
     "SRS 3.5 forbids cross-service foreign keys; each service must be deployable on its own."),
    ("Projections", "Each service keeps the copies it needs, built from events", "Call the owner over REST each time",
     "No runtime dependency on another service being up; matches the event-driven design."),
    ("Ledger placement", "Inside Payment", "Separate Ledger service",
     "A payment and its ledger entries must commit together (SRS 3.3)."),
    ("Network layout", "Public, private app, private data and ops subnets over 2 availability zones",
     "One flat network; everything public behind a firewall",
     "Only the load balancer is reachable from the internet; databases have no internet route (SRS 12, 15)."),
    ("Cloud", "AWS ap-south-1 (Mumbai), drawn with generic names", "Azure Central India; GCP asia-south1",
     "Storage adapter already targets S3 ap-south-1, data stays in India, managed Kafka (MSK) exists."),
    ("Web app", "One Angular app with /shop, /seller, /admin", "Three separate apps",
     "One build and login, shared UI kit; lazy loading keeps each area small."),
    ("Rates", "Commission on the category, fees and TCS/TDS in `rate_configs`, snapshots on order items",
     "Hard-coded rates", "SRS 8.2: rates are configuration with an effective-from date."),
]

OPEN_ITEMS = """
Drawing everything in one place surfaced a few small gaps. None blocks Phase 2; each is a short task.

- **SUPPORT role row.** Identity's V1 migration seeds a `SUPPORT` role, which SRS v1.1 removed. A
later migration should delete it and its description of ADMIN should change to "insights and
exceptions".
- **fulfillment-events topic.** `libs/common` `Topics.java` has no `FULFILLMENT_EVENTS` constant yet.
Add it when Fulfillment is built.
- **Tables added beyond SRS 8.4.** These diagrams add `wishlist_items`, `rate_configs`,
`invoice_items`, Fulfillment's `order_item_projection`, Notification's `user_contacts`,
Recommendation's `product_features` and Insights' `seller_action_items`. Each is marked in the column
tables with the reason. SRS 8.4 could list them in v1.2.
- **Naming.** SRS 8.4 says `recovery_codes`; the built table is `mfa_recovery_codes`.
- **Planned schemas are a design, not a contract.** Column names for Phases 2 to 4 may change when
each service is built; the Flyway migration is the final word, and these diagrams get regenerated.
"""

GLOSSARY = [
    ("ADR", "Architecture decision record: a short note in `docs/adr/` explaining one decision."),
    ("AWB", "Air waybill: the courier's tracking number for a parcel."),
    ("CIDR", "A way to write a range of network addresses, such as 10.0.16.0/20 (4,096 addresses)."),
    ("Correlation id", "One id that follows a user action through every service and event, for tracing logs."),
    ("DFD", "Data-flow diagram: follows information between people, processes and stores."),
    ("DLT", "Dead-letter topic: where Kafka events go after failing all retries."),
    ("ERD", "Entity-relationship diagram: tables and the links between them."),
    ("Event", "A record of a fact that already happened, such as OrderCreated, published on Kafka."),
    ("GMV", "Gross merchandise value: total value of goods sold."),
    ("HSN", "Harmonised System of Nomenclature code, which decides an item's GST rate."),
    ("Idempotent", "Safe to repeat: doing it twice has the same effect as once."),
    ("JWKS", "JSON Web Key Set: the public keys anyone can use to check our access tokens."),
    ("Logical id", "An id copied from another service's data, with no foreign key behind it."),
    ("Mermaid", "A text language for diagrams, drawn by GitHub and many editors."),
    ("NAT gateway", "Lets private machines call out to the internet while blocking calls coming in."),
    ("NDR / RTO", "Non-delivery report (a failed delivery attempt) / return to origin (parcel sent back to the seller)."),
    ("Outbox", "A table where a service saves events in the same transaction as its change, for a relay to publish."),
    ("Projection", "A service's read-only copy of another service's data, built from events."),
    ("Subnet", "A slice of the private network; firewall rules are written between subnets."),
    ("Saga", "A multi-service business process made of local steps, with compensating steps on failure."),
    ("Soft delete", "Marking a row deleted with `deleted_at` instead of removing it."),
    ("VPC", "Virtual private cloud: the company's own isolated network inside the cloud."),
    ("WAF", "Web application firewall: blocks common attacks before they reach the app."),
    ("TCS / TDS", "Tax collected at source (GST) / tax deducted at source (section 194-O), withheld from seller payouts."),
    ("TOTP", "Time-based one-time password: the 6-digit code from an authenticator app."),
]
GLOSSARY.sort(key=lambda row: row[0].lower())
