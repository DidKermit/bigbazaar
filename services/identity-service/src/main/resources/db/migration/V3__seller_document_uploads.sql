-- Real file uploads for seller documents: a row is created when an upload link is issued and moves
-- to UPLOADED once the file has been checked, or INVALID / EXPIRED otherwise.
ALTER TABLE seller_documents
    ADD COLUMN status             VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    ADD COLUMN original_file_name VARCHAR(200),
    ADD COLUMN created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ALTER COLUMN uploaded_at DROP NOT NULL;

ALTER TABLE seller_documents ALTER COLUMN status DROP DEFAULT;
ALTER TABLE seller_documents ALTER COLUMN created_at DROP DEFAULT;

ALTER TABLE seller_documents ADD CONSTRAINT chk_seller_documents_status
    CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'INVALID', 'EXPIRED'));

CREATE UNIQUE INDEX uk_seller_documents_storage_key ON seller_documents (storage_key);
CREATE INDEX idx_seller_documents_pending ON seller_documents (created_at) WHERE status = 'PENDING_UPLOAD';
