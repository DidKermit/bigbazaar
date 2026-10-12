# ADR 0007: Automatic seller verification through a KYC provider

- Status: Accepted
- Date: 2026-10-12
- Changes: FR-SELLER-02 (who approves sellers)

## Context

The SRS has an admin approve every seller (FR-SELLER-02). Large Indian marketplaces check sellers
against government registries instead and only involve a person when something doesn't line up. kai
asked for the same here, replacing manual approval for clean applications.

## Decision

- Four checks run through a KYC provider, anchored on the GST registration's legal name:
  1. **GSTIN** is registered and Active.
  2. **PAN**, taken from characters 3-12 of the GSTIN, is valid and its name and date match.
  3. **Bank account**: a ₹1 penny drop returns the holder's name, which must match the legal name,
     the business name or the account holder's own name.
  4. **Identity**: the seller shares their Aadhaar through DigiLocker; that name must match the legal
     name or their BigBazaar account name.
- All four pass: the seller is approved automatically. The status history and the `SellerApproved`
  event carry a fixed system actor ID (`…a070`) and the reason.
- Anything else (a mismatch, a failed check, missing details, or the provider down after 5 tries):
  the application moves to `UNDER_REVIEW` with every check's result, and an admin decides as before.
  Admins keep approve, reject, suspend and reinstate.
- Names are compared word by word after removing titles and company suffixes. Initials count, and a
  missing middle name still passes (`NameMatcher`, threshold 0.8).
- **Provider: Sandbox (sandbox.co.in)**, chosen by kai after comparing Surepass, Karza, Signzy, IDfy and
  Cashfree. It covers all four checks and has self-serve test keys
  (`/mnt/project-files/notes/kyc-provider-comparison.md`). It sits behind a `KycProvider` interface,
  next to a built-in mock, so another provider is one more adapter.
- **DigiLocker, not Aadhaar numbers.** Consent-based sharing means we never collect or store an
  Aadhaar number.
- Provider calls run in a background worker outside database transactions, never inside a request.
  Rows are claimed with `FOR UPDATE SKIP LOCKED` and a 2-minute lease, so it is safe on several replicas.
- The bank account number and the PAN date are personal data. They are stored AES-GCM encrypted and
  bound to the seller ID; only the IFSC and the last four digits are readable. Check results keep the
  names returned by registries, so an admin can see why a match failed, but no identifiers.

## Consequences

- Clean applications are approved within seconds of the seller finishing DigiLocker.
- Every check costs money per call once on paid keys. Retries are capped, and nothing is called until
  the seller has given everything.
- Companies are usually reviewed by a person, because the director's Aadhaar name rarely matches a
  company's legal name. Proprietorships, the common case for small sellers, pass automatically.
- The KYC document upload from Phase 1 stays as the fallback for sellers who can't use DigiLocker.
- DigiLocker needs the seller's app to open Sandbox's SDK with the session ID. Until the frontend
  exists (Phase 4), the API returns the session ID and the mock completes immediately.
