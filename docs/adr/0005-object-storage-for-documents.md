# ADR 0005: KYC documents in object storage

- Status: Accepted
- Date: 2026-10-12

## Context

Sellers upload identity and business documents (FR-SELLER-04). These are files of up to a few
megabytes that contain personal data. The SRS (2.5, 14) puts files in object storage, not the
database, and asks that sensitive files are never public.

## Decision

- Files go to object storage behind an `ObjectStorage` interface with two adapters:
  - `s3`: AWS S3 or any S3-compatible store. Uploads use presigned PUT URLs whose signature covers
    the content type and length, so the client can't swap in a different file.
  - `local`: files on disk, with HMAC-signed links served by identity through the gateway. For
    development and tests only.
- Two-step upload. `POST /sellers/me/documents/uploads` records a `PENDING_UPLOAD` row and returns a
  15-minute link. The client uploads directly to storage, then calls `.../confirm`. Identity checks
  the stored size and the first bytes (PDF, JPEG or PNG signatures) and marks it `UPLOADED`, or
  deletes it and marks it `INVALID`.
- Downloads are always short-lived signed links, issued only to the applicant or an MFA admin.
- A scheduled job expires uploads not confirmed within 24 hours and deletes their objects.
- Storage keys are random (`sellers/{sellerId}/{uuid}`); the original file name is kept only as
  metadata.

## Consequences

- Large files never pass through the API services' memory as JSON, and the services stay stateless.
- Development needs no extra container. The S3 adapter is tested against S3Mock in CI.
- Virus scanning is not in Phase 1. A scan step can be added between upload and `UPLOADED` later.
