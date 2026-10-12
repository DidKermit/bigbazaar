package com.bigbazaar.identity.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Metadata only; the file itself lives in object storage under {@code storageKey}. */
@Entity
@Table(name = "seller_documents")
public class SellerDocument {

    @Id
    private UUID id;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "document_type", nullable = false)
    private String documentType;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected SellerDocument() {
    }

    SellerDocument(UUID sellerId, String documentType, String storageKey, String contentType, long sizeBytes,
                   Instant uploadedAt) {
        this.id = UUID.randomUUID();
        this.sellerId = sellerId;
        this.documentType = documentType;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getDocumentType() {
        return documentType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
