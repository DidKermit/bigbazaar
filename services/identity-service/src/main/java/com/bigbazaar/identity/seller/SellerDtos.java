package com.bigbazaar.identity.seller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SellerDtos {

    public record SellerApplicationRequest(
            @NotBlank @Size(max = 160) String businessName,
            @NotBlank @Email @Size(max = 254) String businessEmail,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optional leading +")
            String businessPhone,
            // GSTIN format; optional for small sellers below the registration threshold.
            @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "must be a valid GSTIN")
            String taxId,
            @NotBlank @Size(max = 500) String pickupAddress) {
    }

    public record SellerDocumentRequest(
            @NotBlank @Pattern(regexp = "^(PAN|GST_CERTIFICATE|ID_PROOF|ADDRESS_PROOF|BANK_PROOF|OTHER)$")
            String documentType,
            @NotBlank @Size(max = 500) String storageKey,
            @NotBlank @Pattern(regexp = "^(application/pdf|image/jpeg|image/png)$",
                    message = "must be application/pdf, image/jpeg or image/png")
            String contentType,
            @Positive @jakarta.validation.constraints.Max(10 * 1024 * 1024) long sizeBytes) {
    }

    public record SellerDecisionRequest(
            @NotNull SellerDecision decision,
            @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record SellerResponse(
            UUID id,
            UUID userId,
            String businessName,
            String businessEmail,
            String businessPhone,
            String taxId,
            String pickupAddress,
            SellerStatus status,
            Instant createdAt,
            Instant updatedAt) {

        static SellerResponse from(Seller seller) {
            return new SellerResponse(seller.getId(), seller.getUserId(), seller.getBusinessName(),
                    seller.getBusinessEmail(), seller.getBusinessPhone(), seller.getTaxId(),
                    seller.getPickupAddress(), seller.getStatus(), seller.getCreatedAt(), seller.getUpdatedAt());
        }
    }

    public record SellerDocumentResponse(UUID id, String documentType, String storageKey, String contentType,
                                         long sizeBytes, Instant uploadedAt) {
        static SellerDocumentResponse from(SellerDocument document) {
            return new SellerDocumentResponse(document.getId(), document.getDocumentType(), document.getStorageKey(),
                    document.getContentType(), document.getSizeBytes(), document.getUploadedAt());
        }
    }

    public record StatusHistoryEntry(SellerStatus fromStatus, SellerStatus toStatus, String reason, UUID actorId,
                                     UUID correlationId, Instant changedAt) {
        static StatusHistoryEntry from(SellerStatusHistory history) {
            return new StatusHistoryEntry(history.getFromStatus(), history.getToStatus(), history.getReason(),
                    history.getActorId(), history.getCorrelationId(), history.getChangedAt());
        }
    }

    public record SellerDetailResponse(SellerResponse seller, List<SellerDocumentResponse> documents,
                                       List<StatusHistoryEntry> history) {
    }

    private SellerDtos() {
    }
}
