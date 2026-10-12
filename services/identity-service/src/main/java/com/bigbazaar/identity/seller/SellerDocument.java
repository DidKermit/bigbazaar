package com.bigbazaar.identity.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Metadata only; the file itself lives in object storage under {@code storageKey}. */
@Entity
@Table(name = "seller_documents")
public class SellerDocument {

    public enum Status { PENDING_UPLOAD, UPLOADED, INVALID, EXPIRED }

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

    @Column(name = "original_file_name")
    private String originalFileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    protected SellerDocument() {
    }

    SellerDocument(UUID id, UUID sellerId, String documentType, String storageKey, String contentType,
                   long sizeBytes, String originalFileName, Instant now) {
        this.id = id;
        this.sellerId = sellerId;
        this.documentType = documentType;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.originalFileName = originalFileName;
        this.status = Status.PENDING_UPLOAD;
        this.createdAt = now;
    }

    void markUploaded(Instant now) {
        status = Status.UPLOADED;
        uploadedAt = now;
    }

    void markInvalid() {
        status = Status.INVALID;
    }

    void markExpired() {
        status = Status.EXPIRED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSellerId() {
        return sellerId;
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

    public String getOriginalFileName() {
        return originalFileName;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
