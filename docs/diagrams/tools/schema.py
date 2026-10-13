"""Every table and column for the ten service databases (SRS v1.1 section 8).

This file is the single source for both views in the diagrams PDF:
  * the ERD of each database, which shows only the columns marked with a leading "*";
  * the full column tables, which show every column.

Column line format:   [*]name  type[?]  [KEY ...]  [-- note]
  *        main column, drawn in the ERD
  ?        nullable (no "?" means NOT NULL)
  PK       primary key (several PK columns make a composite key)
  UK       unique (the note says when it is a partial unique index)
  FK>t.c   foreign key to table t, column c, inside the same database
  REF>x    logical id owned by another service (no foreign key, SRS 3.5)

Status of each table:
  built    exists today in a Flyway migration
  planned  designed here from the SRS, built in a later phase
  added    planned, and not named in SRS 8.4; the note says why it is needed
"""

from dataclasses import dataclass, field
import re


@dataclass
class Column:
    name: str
    type: str
    nullable: bool
    main: bool
    keys: list
    note: str

    @property
    def pk(self):
        return "PK" in self.keys

    @property
    def uk(self):
        return "UK" in self.keys

    @property
    def fk(self):
        for k in self.keys:
            if k.startswith("FK>"):
                return k[3:]
        return None

    @property
    def ref(self):
        for k in self.keys:
            if k.startswith("REF>"):
                return k[4:]
        return None


@dataclass
class Table:
    name: str
    status: str
    purpose: str
    columns: list
    notes: list = field(default_factory=list)


@dataclass
class Database:
    name: str
    service: str
    status: str
    summary: str
    tables: list
    shared: list  # names of shared tables this database also has


SOFT_DELETE = """
deleted_at timestamptz? -- soft delete (SRS 8.3)
deleted_by uuid?
delete_reason varchar(500)?
"""

TIMESTAMPS = """
created_at timestamptz
updated_at timestamptz
"""

VERSION = """
version bigint -- optimistic locking, default 0
"""


def parse(spec):
    cols = []
    for raw in spec.strip().splitlines():
        line = raw.strip()
        if not line:
            continue
        note = ""
        if "--" in line:
            line, note = line.split("--", 1)
            line, note = line.strip(), note.strip()
        main = line.startswith("*")
        if main:
            line = line[1:]
        parts = line.split()
        name, typ, keys = parts[0], parts[1], parts[2:]
        nullable = typ.endswith("?")
        typ = typ.rstrip("?")
        cols.append(Column(name, typ, nullable, main, keys, note))
    return cols


def T(name, status, purpose, spec, *notes):
    return Table(name, status, purpose, parse(spec), list(notes))


# --------------------------------------------------------------------------------------------
# Tables every event-driven service repeats (SRS 9.2, 9.4). Shown once, in the shared section.
# --------------------------------------------------------------------------------------------

SHARED = [
    T("outbox_events", "built",
      "Events waiting to be published. Written in the same transaction as the business change, "
      "then sent to Kafka by the outbox relay (ADR 0002).", """
*id uuid PK -- also the event id
aggregate_type varchar(40) -- e.g. USER, SELLER
*aggregate_id varchar(64) -- Kafka message key, keeps one entity's events in order
*event_type varchar(80)
*topic varchar(120)
envelope jsonb -- the full EventEnvelope (SRS 9.1)
*status varchar(16) -- PENDING, PUBLISHED, FAILED
attempts int -- default 0
last_error varchar(1000)?
*created_at timestamptz
published_at timestamptz?
""", "Partial index on created_at WHERE status = 'PENDING' so the relay finds unsent rows fast."),
    T("processed_events", "built",
      "Event ids a consumer has already handled, so a redelivered event is skipped "
      "(idempotent consumer, SRS 9.4).", """
*consumer varchar(80) PK -- listener name
*event_id uuid PK
processed_at timestamptz -- default now()
"""),
]


# --------------------------------------------------------------------------------------------
# identity_db: Identity service. Built in Phase 1 (migrations V1 to V4).
# --------------------------------------------------------------------------------------------

IDENTITY = Database("identity_db", "Identity", "built",
    "Users, roles, MFA, sessions, sellers and automatic seller verification.", [
    T("users", "built", "Every person who can log in.", """
*id uuid PK
*name varchar(120)
*email varchar(254) UK -- unique on LOWER(email) WHERE deleted_at IS NULL
*phone varchar(20)
password_hash varchar(100) -- BCrypt (FR-AUTH-02)
*status varchar(32) -- PENDING_VERIFICATION, ACTIVE, LOCKED, SUSPENDED, DEACTIVATED
failed_login_attempts int -- default 0
locked_until timestamptz?
""" + TIMESTAMPS + VERSION + SOFT_DELETE),
    T("roles", "built", "The role catalogue.", """
*name varchar(32) PK -- CUSTOMER, SELLER, ADMIN
description varchar(200)
""", "V1 also seeded a SUPPORT row. SRS v1.1 removed that role, so a later migration should delete it."),
    T("user_roles", "built", "Which roles each user holds (multi-role users, FR-AUTH-05).", """
*user_id uuid PK FK>users.id
*role varchar(32) PK FK>roles.name
granted_at timestamptz -- default now()
"""),
    T("addresses", "built", "Saved delivery addresses. Orders copy them as snapshots.", """
*id uuid PK
*user_id uuid FK>users.id
label varchar(40)?
recipient varchar(120)
phone varchar(20)
line1 varchar(200)
line2 varchar(200)?
*city varchar(100)
*state varchar(100) -- decides CGST+SGST or IGST (FR-TAX-02)
*postal_code varchar(12)
country varchar(2) -- default 'IN'
is_default boolean -- default false
""" + TIMESTAMPS + SOFT_DELETE),
    T("phone_verifications", "planned", "One-time SMS codes that verify a phone before the first order (FR-AUTH-09).", """
*id uuid PK
*user_id uuid FK>users.id
*phone varchar(20)
code_hash varchar(64) -- SHA-256, the code itself is never stored
attempts int -- default 0
*expires_at timestamptz
*verified_at timestamptz?
created_at timestamptz
"""),
    T("user_mfa", "built", "Authenticator-app (TOTP) enrolment per user (FR-AUTH-10, ADR 0004).", """
*user_id uuid PK FK>users.id
totp_secret_encrypted varchar(200) -- AES-GCM
*status varchar(16) -- PENDING, ACTIVE
last_used_step bigint -- blocks replay of the same 30-second code
created_at timestamptz
activated_at timestamptz?
""" + VERSION),
    T("mfa_recovery_codes", "built", "One-time codes for a lost phone (named recovery_codes in SRS 8.4).", """
*id uuid PK
*user_id uuid FK>users.id
code_hash varchar(64) UK
created_at timestamptz
*used_at timestamptz?
"""),
    T("mfa_challenges", "built", "Issued after a correct password when MFA is on; exchanged for tokens with a valid code.", """
*id uuid PK
*user_id uuid FK>users.id
token_hash varchar(64) UK
attempts int -- default 0, max 5
created_at timestamptz
*expires_at timestamptz
consumed_at timestamptz?
"""),
    T("refresh_tokens", "built", "Hashed refresh tokens. A family is one login's rotation chain (FR-AUTH-04, ADR 0003).", """
*id uuid PK
*user_id uuid FK>users.id
*family_id uuid -- reuse of a rotated token revokes the whole family
token_hash varchar(64) UK
*expires_at timestamptz
created_at timestamptz
*revoked_at timestamptz?
revoke_reason varchar(40)?
replaced_by_id uuid?
mfa_verified boolean -- refreshed tokens keep the strength of the login
"""),
    T("sellers", "built", "A seller application and, once approved, the seller account (one per user).", """
*id uuid PK
*user_id uuid UK FK>users.id
*business_name varchar(160)
business_email varchar(254)
business_phone varchar(20)
*tax_id varchar(20)? -- GSTIN
pickup_address varchar(500)
*status varchar(32) -- DRAFT, PENDING_VERIFICATION, UNDER_REVIEW, APPROVED, REJECTED, SUSPENDED, CLOSED
bank_ifsc varchar(11)?
bank_account_last4 varchar(4)? -- only the last four digits are readable
kyc_inputs_enc text? -- AES-GCM: account number, PAN, date (SRS 8.2)
""" + TIMESTAMPS + VERSION),
    T("seller_documents", "built", "KYC files in object storage; the row holds the key, never the file (FR-SELLER-10, ADR 0005).", """
*id uuid PK
*seller_id uuid FK>sellers.id
*document_type varchar(40)
*storage_key varchar(500) UK
content_type varchar(100)
size_bytes bigint
*status varchar(20) -- PENDING_UPLOAD, UPLOADED, INVALID, EXPIRED
original_file_name varchar(200)?
created_at timestamptz
uploaded_at timestamptz?
"""),
    T("seller_verifications", "built", "One automatic verification attempt (ADR 0007).", """
*id uuid PK
*seller_id uuid FK>sellers.id
*status varchar(24) -- AWAITING_IDENTITY, READY, RUNNING, PASSED, NEEDS_REVIEW, SUPERSEDED
provider varchar(20) -- mock, sandbox
identity_session_id varchar(100)? -- DigiLocker session
*attempts int
next_attempt_at timestamptz? -- when the background worker retries
locked_until timestamptz? -- worker lease
outcome_summary varchar(500)?
created_at timestamptz
completed_at timestamptz?
""" + VERSION),
    T("seller_verification_checks", "built", "Result of each check: GSTIN, PAN, bank account, identity. Append-only.", """
*id bigserial PK
*verification_id uuid FK>seller_verifications.id
*check_type varchar(20) -- GSTIN, PAN, BANK_ACCOUNT, IDENTITY
*outcome varchar(16) -- PASS, FAIL, MISMATCH, MISSING, ERROR
detail varchar(500)
registry_name varchar(200)? -- name the registry returned, for the admin
match_score numeric(4,3)?
provider_ref varchar(100)?
checked_at timestamptz
""", "A trigger rejects UPDATE and DELETE."),
    T("seller_status_history", "built", "Every seller status change, never edited (FR-SELLER-03).", """
*id bigserial PK
*seller_id uuid FK>sellers.id
from_status varchar(32)?
*to_status varchar(32)
reason varchar(500)
*actor_id uuid -- user, or the SYSTEM id
correlation_id uuid?
*changed_at timestamptz
""", "A trigger rejects UPDATE and DELETE."),
], ["outbox_events"])


# --------------------------------------------------------------------------------------------
# catalog_db: Catalog service. Phase 2.
# --------------------------------------------------------------------------------------------

CATALOG = Database("catalog_db", "Catalog", "planned",
    "Categories, brands, products, variants, images, prices and automatic listing checks.", [
    T("verified_seller_projection", "planned", "Local copy of approved sellers, built from identity-events (FR-SELLER-04).", """
*seller_id uuid PK REF>identity.sellers
*business_name varchar(160)
*gstin varchar(15)
registered_address varchar(500) -- shown on product pages (FR-CAT-12)
*state_code varchar(2)
*status varchar(32) -- APPROVED, SUSPENDED
last_event_id uuid
updated_at timestamptz
"""),
    T("categories", "planned", "Category tree with the return window and commission rate (FR-CAT-01, FR-RET-02).", """
*id uuid PK
*parent_id uuid? FK>categories.id
*name varchar(120)
slug varchar(140) UK
*returnable boolean
*return_window_days int? -- null when not returnable
*commission_rate numeric(5,4) -- e.g. 0.0800
commission_effective_from date
is_active boolean
""" + TIMESTAMPS + SOFT_DELETE),
    T("brands", "planned", "Brands, managed by admin.", """
*id uuid PK
*name varchar(120) UK
slug varchar(140) UK
logo_storage_key varchar(500)?
*status varchar(16) -- ACTIVE, RESTRICTED
""" + TIMESTAMPS + SOFT_DELETE),
    T("products", "planned", "A listing owned by one seller.", """
*id uuid PK
*seller_id uuid REF>identity.sellers
*category_id uuid FK>categories.id
*brand_id uuid? FK>brands.id
*title varchar(200)
slug varchar(220) UK
description text
*status varchar(24) -- DRAFT, PENDING_CHECKS, PENDING_APPROVAL, ACTIVE, INACTIVE, OUT_OF_STOCK, REJECTED, SUSPENDED, DELETED
*hsn_code varchar(8) -- FR-CAT-11
*gst_rate numeric(5,4)
country_of_origin varchar(2) -- FR-CAT-12
manufacturer_details varchar(500)?
is_packaged boolean -- MRP and net quantity required when true
trust_score numeric(5,2)? -- copied from trust-events
rejection_reason varchar(500)?
published_at timestamptz?
""" + TIMESTAMPS + VERSION + SOFT_DELETE),
    T("product_variants", "planned", "A buyable SKU: size, colour and so on.", """
*id uuid PK
*product_id uuid FK>products.id
*sku varchar(64) UK -- unique WHERE deleted_at IS NULL (FR-CAT-04)
*attributes jsonb -- {"size": "M", "colour": "blue"}
*price numeric(12,2)
*mrp numeric(12,2)?
net_quantity numeric(10,3)?
net_quantity_unit varchar(10)?
weight_grams int -- for shipping
is_active boolean
""" + TIMESTAMPS + VERSION + SOFT_DELETE),
    T("product_images", "planned", "Image metadata; the file lives in object storage (FR-CAT-05).", """
*id uuid PK
*product_id uuid FK>products.id
variant_id uuid? FK>product_variants.id
*storage_key varchar(500)
width int
height int
sort_order int
alt_text varchar(200)?
created_at timestamptz
deleted_at timestamptz?
"""),
    T("price_history", "planned", "Every price change, never edited (FR-CAT-07).", """
*id bigserial PK
*variant_id uuid FK>product_variants.id
old_price numeric(12,2)?
*new_price numeric(12,2)
old_mrp numeric(12,2)?
new_mrp numeric(12,2)?
changed_by uuid
*changed_at timestamptz
"""),
    T("listing_checks", "planned", "Result of each automatic listing rule (FR-CAT-10).", """
*id bigserial PK
*product_id uuid FK>products.id
attempt int
*rule varchar(40) -- BANNED_ITEM, BANNED_WORDS, REQUIRED_DETAILS, IMAGE_QUALITY, CATEGORY_FIT, BRAND_RULE
*outcome varchar(10) -- PASS, FAIL, UNSURE
detail varchar(500)
checked_by varchar(20) -- SYSTEM, AI_SERVICE
checked_at timestamptz
"""),
], ["outbox_events", "processed_events"])


# --------------------------------------------------------------------------------------------
# commerce_db: Commerce service. Phase 2.
# --------------------------------------------------------------------------------------------

COMMERCE = Database("commerce_db", "Commerce", "planned",
    "Inventory and reservations, cart, wishlist, orders split per seller, and dispatch deadlines.", [
    T("product_projection", "planned", "Local copy of sellable variants, built from product-events.", """
*variant_id uuid PK REF>catalog.product_variants
*product_id uuid REF>catalog.products
*seller_id uuid REF>identity.sellers
category_id uuid REF>catalog.categories
sku varchar(64)
title varchar(200)
attributes jsonb
*price numeric(12,2)
mrp numeric(12,2)?
hsn_code varchar(8)
gst_rate numeric(5,4)
commission_rate numeric(5,4)
return_window_days int?
*status varchar(24)
last_event_id uuid
updated_at timestamptz
"""),
    T("inventory", "planned", "Stock per variant. available = on_hand - reserved (SRS 8.6).", """
*variant_id uuid PK FK>product_projection.variant_id
*seller_id uuid REF>identity.sellers
*on_hand_quantity int
*reserved_quantity int
low_stock_threshold int? -- FR-INV-06
updated_at timestamptz
""" + VERSION, "CHECK (on_hand_quantity >= reserved_quantity) stops overselling (FR-INV-05)."),
    T("stock_reservations", "planned", "Stock held for an order during checkout, with an expiry (FR-INV-04).", """
*id uuid PK
*variant_id uuid FK>inventory.variant_id
*order_id uuid FK>orders.id
*quantity int
*status varchar(16) -- ACTIVE, CONFIRMED, RELEASED, EXPIRED, CANCELLED
*expires_at timestamptz
""" + TIMESTAMPS, "Partial index on (status, expires_at) WHERE status = 'ACTIVE' for the expiry job."),
    T("stock_movements", "planned", "Every stock change, never edited (FR-INV-03).", """
*id bigserial PK
*variant_id uuid FK>inventory.variant_id
*movement_type varchar(16) -- ADD, ADJUST, RESERVE, RELEASE, CONFIRM, RESTOCK
*quantity_delta int
on_hand_after int
reserved_after int
reference_type varchar(30)? -- ORDER, RETURN, MANUAL
reference_id uuid?
actor_id uuid
*created_at timestamptz
"""),
    T("carts", "planned", "One open cart per customer.", """
*id uuid PK
*user_id uuid UK REF>identity.users
""" + TIMESTAMPS),
    T("cart_items", "planned", "Lines in a cart. Checkout re-reads price and stock (FR-CART-02).", """
*id uuid PK
*cart_id uuid FK>carts.id
*variant_id uuid FK>product_projection.variant_id -- unique per cart
*quantity int
price_at_add numeric(12,2) -- display only, never charged
added_at timestamptz
updated_at timestamptz
"""),
    T("wishlists", "planned", "One wishlist per customer.", """
*id uuid PK
*user_id uuid UK REF>identity.users
created_at timestamptz
"""),
    T("wishlist_items", "added", "Lines in a wishlist; the price at add time drives the price-drop alert (FR-WISH-02).", """
*id uuid PK
*wishlist_id uuid FK>wishlists.id
*product_id uuid REF>catalog.products
variant_id uuid? REF>catalog.product_variants
*price_at_add numeric(12,2)
added_at timestamptz
""", "Not named in SRS 8.4, which lists only wishlists; a wishlist needs its items."),
    T("orders", "planned", "The customer's order, with address snapshots (SRS 8.7).", """
*id uuid PK
*order_number varchar(20) UK -- human-readable
*user_id uuid REF>identity.users
*status varchar(24) -- CREATED ... REFUNDED (Appendix A)
subtotal_amount numeric(12,2)
tax_amount numeric(12,2)
shipping_amount numeric(12,2)
*total_amount numeric(12,2)
currency varchar(3) -- INR
shipping_address jsonb -- snapshot
billing_address jsonb -- snapshot
buyer_state_code varchar(2)
placed_at timestamptz
""" + TIMESTAMPS + VERSION, "Index on (user_id, created_at DESC) for 'my orders'."),
    T("seller_orders", "planned", "The part of an order one seller fulfils (FR-ORD-02).", """
*id uuid PK
*order_id uuid FK>orders.id
*seller_id uuid REF>identity.sellers
*status varchar(24) -- CONFIRMED, PACKED, SHIPPED, DELIVERED, CANCELLED, RETURNED_TO_ORIGIN
subtotal_amount numeric(12,2)
tax_amount numeric(12,2)
*total_amount numeric(12,2)
*dispatch_deadline_at timestamptz? -- FR-SELLER-08
confirmed_at timestamptz?
packed_at timestamptz?
shipped_at timestamptz?
delivered_at timestamptz?
return_window_ends_at timestamptz?
cancelled_at timestamptz?
cancel_reason varchar(200)?
""" + TIMESTAMPS + VERSION),
    T("order_items", "planned", "One line, with immutable product, price and tax snapshots (FR-ORD-03).", """
*id uuid PK
*order_id uuid FK>orders.id
*seller_order_id uuid FK>seller_orders.id
*variant_id uuid REF>catalog.product_variants
product_id uuid REF>catalog.products
sku varchar(64)
product_title varchar(200)
variant_attributes jsonb
*quantity int
*unit_price numeric(12,2)
mrp numeric(12,2)?
hsn_code varchar(8)
gst_rate numeric(5,4)
tax_amount numeric(12,2)
line_total numeric(12,2)
commission_rate numeric(5,4) -- snapshot so later rate changes do not alter this order
return_window_days int?
*status varchar(24) -- ORDERED, CANCELLED, RETURN_REQUESTED, RETURNED
created_at timestamptz
"""),
    T("order_status_history", "planned", "Every order and seller-order status change, never edited (FR-ORD-04).", """
*id bigserial PK
*order_id uuid FK>orders.id
seller_order_id uuid? FK>seller_orders.id
from_status varchar(24)?
*to_status varchar(24)
reason varchar(500)?
actor_id uuid
correlation_id uuid?
*changed_at timestamptz
"""),
    T("idempotency_keys", "planned", "Makes checkout safe to retry: the same key returns the same order (FR-ORD-05).", """
*user_id uuid PK REF>identity.users
*idempotency_key varchar(64) PK
request_hash varchar(64) -- same key with a different body is rejected
*order_id uuid? FK>orders.id
response_status int?
response_body jsonb?
created_at timestamptz
expires_at timestamptz
"""),
], ["outbox_events", "processed_events"])


# --------------------------------------------------------------------------------------------
# payment_db: Payment and settlement service. Phase 3.
# --------------------------------------------------------------------------------------------

PAYMENT = Database("payment_db", "Payment and settlement", "planned",
    "Payments and refunds, the double-entry ledger, settlements, payouts and GST invoices.", [
    T("payments", "planned", "One payment per order (FR-PAY-01).", """
*id uuid PK
*order_id uuid UK REF>commerce.orders
*user_id uuid REF>identity.users
*amount numeric(12,2)
currency varchar(3)
*status varchar(24) -- CREATED, PENDING, AUTHORIZED, CAPTURED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED
provider varchar(20)
provider_order_id varchar(100)?
""" + TIMESTAMPS + VERSION),
    T("payment_attempts", "planned", "Each try at paying (FR-PAY-03). Card numbers are never stored (FR-PAY-08).", """
*id uuid PK
*payment_id uuid FK>payments.id
provider_payment_id varchar(100)?
*method varchar(20) -- UPI, CARD, NETBANKING, WALLET
*status varchar(16) -- STARTED, SUCCEEDED, FAILED
amount numeric(12,2)
failure_code varchar(40)?
failure_reason varchar(500)?
created_at timestamptz
completed_at timestamptz?
"""),
    T("payment_webhook_events", "planned", "Raw provider webhooks, deduplicated (FR-PAY-04).", """
*id uuid PK
*provider varchar(20) UK -- unique with provider_event_id
*provider_event_id varchar(100) UK
event_type varchar(60)
payload jsonb
signature_valid boolean
received_at timestamptz
*processed_at timestamptz?
processing_error varchar(1000)?
"""),
    T("refunds", "planned", "Refunds to the original method, full or partial (FR-PAY-07).", """
*id uuid PK
*payment_id uuid FK>payments.id
*seller_order_id uuid REF>commerce.seller_orders
order_item_id uuid? REF>commerce.order_items
*amount numeric(12,2)
*reason varchar(24) -- CANCELLED, RETURNED, RTO, DISPATCH_MISSED, PAYMENT_ERROR
*status varchar(16) -- REQUESTED, PROCESSING, COMPLETED, FAILED
provider_refund_id varchar(100)?
idempotency_key varchar(64) UK
requested_at timestamptz
completed_at timestamptz?
"""),
    T("ledger_accounts", "planned", "Ledger accounts: platform-wide ones and one payable account per seller (SRS 8.8).", """
*id uuid PK
*code varchar(30) UK -- unique with owner_id: CUSTOMER_PAYMENTS, PLATFORM_COMMISSION, PLATFORM_FEES, TCS_WITHHELD, TDS_WITHHELD, SELLER_PAYABLE, REFUNDS, PAYOUTS
*owner_type varchar(10) -- PLATFORM, SELLER
*owner_id uuid? UK REF>identity.sellers
normal_balance varchar(6) -- DEBIT, CREDIT
created_at timestamptz
"""),
    T("ledger_entries", "planned", "Double-entry lines. Each transaction's debits equal its credits; append-only (FR-SETL-01).", """
*id bigserial PK
*transaction_id uuid -- groups the entries of one money movement
*account_id uuid FK>ledger_accounts.id
*debit_amount numeric(12,2) -- one of debit or credit is zero
*credit_amount numeric(12,2)
*reference_type varchar(20) -- PAYMENT, REFUND, COMMISSION, FEE, TCS, TDS, PAYOUT
reference_id uuid
description varchar(200)
reverses_entry_id bigint? FK>ledger_entries.id -- corrections are reversing entries
created_at timestamptz
""", "A deferred constraint trigger checks SUM(debit) = SUM(credit) per transaction_id at commit; UPDATE and DELETE are rejected."),
    T("rate_configs", "added", "Fee, TCS and TDS rates with an effective-from date (SRS 8.2, FR-TAX-03).", """
*id uuid PK
*rate_type varchar(16) -- FIXED_FEE, TCS, TDS
rate numeric(6,5)?
fixed_amount numeric(12,2)?
*effective_from date
effective_to date?
created_by uuid
created_at timestamptz
""", "Not named in SRS 8.4. SRS 8.2 requires rates in configuration with an effective-from date; "
         "the commission rate comes from the category, so only fees and tax rates live here."),
    T("settlements", "planned", "One settlement per seller per cycle (FR-SETL-04).", """
*id uuid PK
*seller_id uuid UK REF>identity.sellers -- unique with cycle_start
*cycle_start date UK
*cycle_end date
gross_amount numeric(12,2)
commission_amount numeric(12,2)
fee_amount numeric(12,2)
tcs_amount numeric(12,2)
tds_amount numeric(12,2)
refund_amount numeric(12,2)
*net_payable numeric(12,2)
*status varchar(12) -- PENDING, PAYABLE, PAID, FAILED
created_at timestamptz
paid_at timestamptz?
"""),
    T("settlement_items", "planned", "The seller orders inside a settlement, for the statement (FR-SETL-05).", """
*id uuid PK
*settlement_id uuid FK>settlements.id
*seller_order_id uuid UK REF>commerce.seller_orders -- paid once only
gross_amount numeric(12,2)
commission_amount numeric(12,2)
fee_amount numeric(12,2)
tcs_amount numeric(12,2)
tds_amount numeric(12,2)
refund_amount numeric(12,2)
*net_amount numeric(12,2)
created_at timestamptz
"""),
    T("payouts", "planned", "Bank transfer of a settlement through the payout adapter.", """
*id uuid PK
*settlement_id uuid UK FK>settlements.id
*seller_id uuid REF>identity.sellers
*amount numeric(12,2)
*status varchar(16) -- INITIATED, COMPLETED, FAILED
provider varchar(20)
provider_payout_id varchar(100)?
bank_account_last4 varchar(4)
initiated_at timestamptz
completed_at timestamptz?
failure_reason varchar(500)?
"""),
    T("invoices", "planned", "GST invoice in the seller's name per seller order (FR-TAX-01).", """
*id uuid PK
*invoice_number varchar(30) UK
*seller_order_id uuid UK REF>commerce.seller_orders
*seller_id uuid REF>identity.sellers
seller_gstin varchar(15)
seller_state_code varchar(2)
buyer_state_code varchar(2)
*tax_type varchar(6) -- INTRA (CGST+SGST) or INTER (IGST), FR-TAX-02
taxable_amount numeric(12,2)
cgst_amount numeric(12,2)
sgst_amount numeric(12,2)
igst_amount numeric(12,2)
*total_amount numeric(12,2)
storage_key varchar(500) -- the PDF
issued_at timestamptz
"""),
    T("invoice_items", "added", "Invoice lines, each with its own HSN rate (FR-TAX-02).", """
*id uuid PK
*invoice_id uuid FK>invoices.id
*order_item_id uuid REF>commerce.order_items
description varchar(200)
*hsn_code varchar(8)
quantity int
unit_price numeric(12,2)
taxable_amount numeric(12,2)
*gst_rate numeric(5,4)
cgst_amount numeric(12,2)
sgst_amount numeric(12,2)
igst_amount numeric(12,2)
"""),
], ["outbox_events", "processed_events"])


# --------------------------------------------------------------------------------------------
# fulfillment_db: Fulfillment service. Phase 3.
# --------------------------------------------------------------------------------------------

FULFILLMENT = Database("fulfillment_db", "Fulfillment", "planned",
    "Serviceability, shipments, courier tracking, failed deliveries (NDR/RTO), returns and reverse pickups.", [
    T("serviceable_pincodes", "planned", "Which pincodes each courier delivers to, and how fast (FR-SHIP-01).", """
*pincode varchar(6) PK
*courier varchar(20) PK
city varchar(100)
state_code varchar(2)
*estimated_days int
is_active boolean
updated_at timestamptz
"""),
    T("order_item_projection", "added", "Order lines from commerce-events, to check return windows and quantities.", """
*order_item_id uuid PK REF>commerce.order_items
*seller_order_id uuid REF>commerce.seller_orders
*user_id uuid REF>identity.users
variant_id uuid REF>catalog.product_variants
quantity int
*return_window_days int? -- null means non-returnable
last_event_id uuid
""", "Not named in SRS 8.4; Fulfillment needs it to validate return requests on its own."),
    T("shipments", "planned", "One forward shipment per seller order (FR-SHIP-02).", """
*id uuid PK
*seller_order_id uuid UK REF>commerce.seller_orders
*order_id uuid REF>commerce.orders
*seller_id uuid REF>identity.sellers
*status varchar(24) -- BOOKED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, RETURNED_TO_ORIGIN
*courier varchar(20)
*awb_number varchar(40) UK
label_storage_key varchar(500)
pickup_address jsonb
delivery_address jsonb
delivery_attempts int
max_delivery_attempts int -- RTO after this (FR-SHIP-04)
estimated_delivery_date date
booked_at timestamptz
picked_up_at timestamptz?
delivered_at timestamptz?
returned_to_origin_at timestamptz?
updated_at timestamptz
""" + VERSION),
    T("shipment_events", "planned", "Tracking timeline for a shipment, never edited.", """
*id bigserial PK
*shipment_id uuid FK>shipments.id
*status varchar(24)
location varchar(120)?
description varchar(300)?
*occurred_at timestamptz
source varchar(10) -- COURIER, SYSTEM
courier_webhook_event_id uuid? FK>courier_webhook_events.id
recorded_at timestamptz
"""),
    T("courier_webhook_events", "planned", "Raw signed courier webhooks, deduplicated (FR-SHIP-03).", """
*id uuid PK
*provider varchar(20) UK -- unique with provider_event_id
*provider_event_id varchar(100) UK
awb_number varchar(40)
event_type varchar(40)
payload jsonb
signature_valid boolean
received_at timestamptz
*processed_at timestamptz?
processing_error varchar(1000)?
"""),
    T("return_requests", "planned", "A customer's return of one order item (FR-RET-03).", """
*id uuid PK
*order_item_id uuid FK>order_item_projection.order_item_id
*seller_order_id uuid REF>commerce.seller_orders
*user_id uuid REF>identity.users
quantity int
*reason varchar(40) -- DAMAGED, WRONG_ITEM, NOT_AS_DESCRIBED, SIZE_ISSUE, OTHER
comment varchar(1000)?
photo_storage_keys jsonb?
*status varchar(20) -- REQUESTED, PICKUP_SCHEDULED, PICKED_UP, RECEIVED, REFUNDED, REJECTED
resellable boolean? -- set when the seller receives it
rejection_reason varchar(500)?
requested_at timestamptz
received_at timestamptz?
updated_at timestamptz
""" + VERSION),
    T("reverse_pickups", "planned", "Courier pickup that brings a return back to the seller (FR-RET-04).", """
*id uuid PK
*return_request_id uuid UK FK>return_requests.id
courier varchar(20)
*awb_number varchar(40) UK
*status varchar(20) -- BOOKED, PICKED_UP, IN_TRANSIT, DELIVERED_TO_SELLER, FAILED
scheduled_for date
picked_up_at timestamptz?
delivered_to_seller_at timestamptz?
updated_at timestamptz
"""),
], ["outbox_events", "processed_events"])


# --------------------------------------------------------------------------------------------
# notification_db: Notification service. Phase 3.
# --------------------------------------------------------------------------------------------

NOTIFICATION = Database("notification_db", "Notification", "planned",
    "Email, SMS and in-app messages made from events, with retries and delivery history.", [
    T("user_contacts", "added", "Email and phone per user, from identity-events, so messages can be sent.", """
*user_id uuid PK REF>identity.users
*email varchar(254)
*phone varchar(20)
name varchar(120)
last_event_id uuid
updated_at timestamptz
""", "Not named in SRS 8.4; events carry ids, so Notification keeps its own contact copy."),
    T("notifications", "planned", "One message to one user on one channel (FR-NOT-01).", """
*id uuid PK
*user_id uuid FK>user_contacts.user_id
*channel varchar(10) -- EMAIL, SMS, IN_APP
*template varchar(60)
subject varchar(200)?
body text
*status varchar(10) -- PENDING, SENT, FAILED
source_event_id uuid UK -- unique with channel: one message per event per channel
source_event_type varchar(80)
read_at timestamptz? -- in-app only
created_at timestamptz
sent_at timestamptz?
"""),
    T("delivery_attempts", "planned", "Each send attempt and its result, for retries.", """
*id bigserial PK
*notification_id uuid FK>notifications.id
attempt_no int
provider varchar(20)
provider_message_id varchar(100)?
*outcome varchar(10) -- SENT, FAILED
error varchar(500)?
attempted_at timestamptz
"""),
    T("preferences", "planned", "Which messages a user wants on which channel.", """
*user_id uuid PK FK>user_contacts.user_id
*channel varchar(10) PK
*topic varchar(30) PK -- ORDER_UPDATES, PRICE_DROPS, PROMOTIONS
enabled boolean
updated_at timestamptz
""", "Security codes (OTP, MFA) ignore preferences and are always sent."),
], ["processed_events"])


# --------------------------------------------------------------------------------------------
# trust_db: Trust service. Phase 3.
# --------------------------------------------------------------------------------------------

TRUST = Database("trust_db", "Trust", "planned",
    "Verified reviews, fake-review flags, product and seller trust scores with history.", [
    T("delivered_item_projection", "planned", "Delivered order items, the proof a reviewer bought the product (FR-REV-01).", """
*order_item_id uuid PK REF>commerce.order_items
*user_id uuid REF>identity.users
*product_id uuid REF>catalog.products
*seller_id uuid REF>identity.sellers
variant_id uuid REF>catalog.product_variants
*delivered_at timestamptz
returned boolean
last_event_id uuid
"""),
    T("reviews", "planned", "A verified buyer's review (FR-REV-01, FR-REV-02).", """
*id uuid PK
*order_item_id uuid UK FK>delivered_item_projection.order_item_id -- unique WHERE deleted_at IS NULL
*user_id uuid REF>identity.users
*product_id uuid REF>catalog.products
seller_id uuid REF>identity.sellers
*rating smallint -- 1 to 5
title varchar(150)?
body text?
*status varchar(20) -- PUBLISHED, PENDING_MODERATION, FLAGGED, HIDDEN, REMOVED
published_at timestamptz?
""" + TIMESTAMPS + SOFT_DELETE),
    T("review_flags", "planned", "A reason a review looks fake; admin decides (FR-REV-04).", """
*id uuid PK
*review_id uuid FK>reviews.id
*rule varchar(20) -- BURST, SELLER_LINKED, NEAR_DUPLICATE, USER_REPORT
detail varchar(500)
score numeric(4,3)?
*status varchar(10) -- OPEN, UPHELD, DISMISSED
decided_by uuid?
decision_reason varchar(500)?
created_at timestamptz
decided_at timestamptz?
"""),
    T("review_summaries", "planned", "AI summary of what reviewers praise and complain about (FR-INS-02).", """
*product_id uuid PK REF>catalog.products
praise text
complaints text
review_count int
model_version varchar(40)
*generated_at timestamptz
"""),
    T("product_scores", "planned", "Current product trust score (FR-TRUST-01).", """
*product_id uuid PK REF>catalog.products
*score numeric(5,2)
avg_rating numeric(3,2)
review_count int
components jsonb -- the reasons behind the score
algorithm_version varchar(20)
updated_at timestamptz
"""),
    T("seller_scores", "planned", "Current seller trust score with delivery and return performance (FR-TRUST-02, FR-SELLER-09).", """
*seller_id uuid PK REF>identity.sellers
*score numeric(5,2)
orders_count int
cancelled_count int
late_dispatch_count int
returned_count int
*cancellation_rate numeric(5,4)
*late_dispatch_rate numeric(5,4)
*return_rate numeric(5,4)
avg_rating numeric(3,2)
components jsonb
*flagged boolean -- crossed a threshold, admin reviews
algorithm_version varchar(20)
updated_at timestamptz
"""),
    T("score_history", "planned", "Every score change with its reason components, never edited (FR-TRUST-03).", """
*id bigserial PK
*subject_type varchar(10) -- PRODUCT, SELLER
*subject_id uuid
old_score numeric(5,2)?
*new_score numeric(5,2)
components jsonb
trigger_event_id uuid?
*recorded_at timestamptz
"""),
], ["outbox_events", "processed_events"])


# --------------------------------------------------------------------------------------------
# recommendation_db: Recommendation service (and AI call log). Phase 4.
# --------------------------------------------------------------------------------------------

RECOMMENDATION = Database("recommendation_db", "Recommendation", "planned",
    "User activity, ranking inputs, recommendation logs with reasons, and the AI call log.", [
    T("product_features", "added", "Ranking inputs per product, from product and trust events.", """
*product_id uuid PK REF>catalog.products
*category_id uuid REF>catalog.categories
brand_id uuid? REF>catalog.brands
*min_price numeric(12,2)
trust_score numeric(5,2)?
avg_rating numeric(3,2)?
attributes jsonb
*active boolean -- only active products can be recommended (FR-AI-02)
last_event_id uuid
updated_at timestamptz
""", "Not named in SRS 8.4; content-based ranking (FR-REC-02) needs product attributes locally."),
    T("user_activity", "planned", "Views, searches, cart, wishlist, purchase and review activity (FR-REC-01).", """
*id bigserial PK
*user_id uuid? REF>identity.users -- null for anonymous sessions
session_id varchar(64)
*activity_type varchar(16) -- VIEW, SEARCH, CART, WISHLIST, PURCHASE, REVIEW, CLICK
*product_id uuid? FK>product_features.product_id
query_text varchar(300)?
metadata jsonb?
*occurred_at timestamptz
"""),
    T("user_preferences", "planned", "Learned preferences per user.", """
*user_id uuid PK REF>identity.users
preferred_categories jsonb
preferred_brands jsonb
price_band_min numeric(12,2)?
price_band_max numeric(12,2)?
updated_at timestamptz
"""),
    T("ai_requests", "planned", "Every AI call with its time and cost limit result (FR-AI-04).", """
*id uuid PK
user_id uuid? REF>identity.users
*request_type varchar(20) -- INTENT, COMPARE, EXPLAIN, REVIEW_SUMMARY, LISTING_CHECK
prompt_text text
response jsonb?
model varchar(60)
input_tokens int
output_tokens int
cost_usd numeric(10,6)
*latency_ms int
*status varchar(10) -- OK, TIMEOUT, ERROR, REJECTED
created_at timestamptz
"""),
    T("recommendation_logs", "planned", "One recommendation request and the algorithm version used (FR-REC-03).", """
*id uuid PK
*user_id uuid? REF>identity.users
*context varchar(16) -- HOME, PRODUCT_PAGE, CART, AI_ASSISTANT
*algorithm_version varchar(20)
request_params jsonb
ai_request_id uuid? FK>ai_requests.id
created_at timestamptz
"""),
    T("recommendation_items", "planned", "Each recommended product with its rank and reasons.", """
*id bigserial PK
*log_id uuid FK>recommendation_logs.id
*product_id uuid FK>product_features.product_id
*rank int
score numeric(8,5)
*reasons jsonb -- e.g. ["matches your budget", "high trust score"]
clicked_at timestamptz?
"""),
    T("product_similarity", "planned", "Precomputed similar products.", """
*product_id uuid PK FK>product_features.product_id
*similar_product_id uuid PK FK>product_features.product_id
*score numeric(6,5)
method varchar(20) -- CONTENT, CO_PURCHASE
computed_at timestamptz
"""),
], ["outbox_events", "processed_events"])


# --------------------------------------------------------------------------------------------
# insights_db: Insights service. Phase 4. Read-only totals built from events.
# --------------------------------------------------------------------------------------------

INSIGHTS = Database("insights_db", "Insights", "planned",
    "Read-only daily totals, alerts and report snapshots for the seller and admin dashboards (SRS 5.13).", [
    T("daily_platform_totals", "planned", "Platform totals per day (FR-INS-04).", """
*day date PK
*orders_count int
*gmv numeric(14,2) -- gross merchandise value
net_revenue numeric(14,2) -- commission and fees
visitors_count int
conversion_rate numeric(5,4)
cancellations_count int
returns_count int
refunds_amount numeric(14,2)
payment_failures_count int
new_customers_count int
new_sellers_count int
updated_at timestamptz
"""),
    T("daily_seller_totals", "planned", "Seller totals per day (FR-INS-01).", """
*day date PK
*seller_id uuid PK REF>identity.sellers
*orders_count int
*revenue numeric(14,2)
units_sold int
cancellations_count int
late_dispatch_count int
returns_count int
refunds_amount numeric(14,2)
payouts_amount numeric(14,2)
updated_at timestamptz
"""),
    T("daily_product_totals", "planned", "Product totals per day, for trends and top products (FR-INS-02).", """
*day date PK
*product_id uuid PK REF>catalog.products
*seller_id uuid REF>identity.sellers
views_count int
add_to_cart_count int
*units_sold int
*revenue numeric(14,2)
returns_count int
updated_at timestamptz
"""),
    T("seller_action_items", "added", "What needs the seller's attention now: dispatch due, low stock, latest settlement (FR-INS-03).", """
*id uuid PK
*seller_id uuid REF>identity.sellers
*item_type varchar(20) -- DISPATCH_DUE, LOW_STOCK, SETTLEMENT
*reference_id uuid -- seller order, variant or settlement
due_at timestamptz?
detail jsonb
*open boolean
updated_at timestamptz
""", "Not named in SRS 8.4. SRS 3.5 says dashboards read only from Insights, so these live here too."),
    T("alert_feed", "planned", "Alerts and exceptions waiting for an admin decision (FR-INS-06).", """
*id uuid PK
*alert_type varchar(24) -- FAKE_REVIEW, SELLER_FLAGGED, SELLER_REVIEW, LISTING_REVIEW, RISK, SUPPORT_TICKET
severity varchar(8) -- LOW, MEDIUM, HIGH
*subject_type varchar(20)
*subject_id uuid
title varchar(200)
detail jsonb
*status varchar(10) -- OPEN, RESOLVED
source_event_id uuid UK
created_at timestamptz
resolved_at timestamptz?
"""),
    T("report_runs", "planned", "Generated reports; a run is a snapshot and is never edited (FR-INS-08).", """
*id uuid PK
*report_type varchar(30)
*period_from date
*period_to date
*format varchar(4) -- CSV, PDF
*status varchar(10) -- QUEUED, RUNNING, DONE, FAILED
storage_key varchar(500)?
row_count int?
checksum varchar(64)?
requested_by uuid REF>identity.users
created_at timestamptz
completed_at timestamptz?
"""),
], ["processed_events"])


# --------------------------------------------------------------------------------------------
# platform_db: Audit service. Built in Phase 1 (audit_events); support tickets later.
# --------------------------------------------------------------------------------------------

PLATFORM = Database("platform_db", "Audit (and support tickets)", "built",
    "The append-only audit log of every event, and support tickets answered by admin.", [
    T("audit_events", "built", "One row per domain event; append-only (FR-AUD-01 to 03, ADR 0006).", """
*id bigserial PK
*event_id uuid UK -- replays cannot duplicate
*event_type varchar(80)
event_version int
*source_service varchar(60)
*actor_id uuid?
*entity_type varchar(40)
*entity_id varchar(64)
reason varchar(1000)?
*correlation_id uuid?
*occurred_at timestamptz
recorded_at timestamptz -- default now()
details jsonb
""", "A trigger rejects UPDATE and DELETE. Indexes: (entity_type, entity_id, occurred_at), "
         "actor_id, occurred_at, correlation_id."),
    T("support_tickets", "planned", "A customer's support request, answered by admin (FR-SUP-01).", """
*id uuid PK
*ticket_number varchar(20) UK
*user_id uuid REF>identity.users
order_id uuid? REF>commerce.orders
*subject varchar(200)
category varchar(30) -- ORDER, PAYMENT, RETURN, ACCOUNT, OTHER
*status varchar(16) -- OPEN, IN_PROGRESS, WAITING_ON_CUSTOMER, RESOLVED, CLOSED
priority varchar(8)
assigned_admin_id uuid? REF>identity.users
""" + TIMESTAMPS + """
resolved_at timestamptz?
"""),
    T("ticket_messages", "planned", "The conversation on a ticket, never edited.", """
*id bigserial PK
*ticket_id uuid FK>support_tickets.id
*author_id uuid REF>identity.users
*author_role varchar(10) -- CUSTOMER, ADMIN
body text
attachment_keys jsonb?
*created_at timestamptz
"""),
], ["processed_events"])


DATABASES = [IDENTITY, CATALOG, COMMERCE, PAYMENT, FULFILLMENT, NOTIFICATION, TRUST,
             RECOMMENDATION, INSIGHTS, PLATFORM]

# Search keeps no SQL database; its OpenSearch index document is listed for completeness.
SEARCH_INDEX = T("products (OpenSearch index)", "planned",
    "One document per active product, built from product, stock and trust events (FR-SRCH-01).", """
*product_id keyword PK
*title text -- analysed for keyword search
description text
*category_path keyword -- e.g. electronics/phones
*brand keyword
*seller_id keyword
seller_name keyword
*min_price scaled_float
max_price scaled_float
attributes object -- facets such as size and colour
*in_stock boolean
avg_rating float
trust_score float
review_count integer
*status keyword
published_at date
suggest completion -- autocomplete (FR-SRCH-03)
""")


# --------------------------------------------------------------------------------------------
# Validation: every FK points at a real table and column in the same database.
# --------------------------------------------------------------------------------------------

def validate():
    for db in DATABASES:
        names = {t.name: {c.name for c in t.columns} for t in db.tables}
        for t in db.tables:
            for c in t.columns:
                if c.fk:
                    tbl, col = c.fk.split(".")
                    assert tbl in names, f"{db.name}.{t.name}.{c.name}: no table {tbl}"
                    assert col in names[tbl], f"{db.name}.{t.name}.{c.name}: no column {c.fk}"
            assert any(c.pk for c in t.columns), f"{db.name}.{t.name}: no primary key"


validate()
