package com.bigbazaar.identity.seller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            @NotBlank @Size(max = 500) String pickupAddress,
            // For automatic verification (ADR 0007). Without them the application goes to an admin.
            @Pattern(regexp = "^(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[0-2])/(19|20)[0-9]{2}$",
                    message = "must be DD/MM/YYYY")
            String panDate,
            @Pattern(regexp = "^[0-9]{6,18}$", message = "must be 6-18 digits") String bankAccountNumber,
            @Pattern(regexp = "^[A-Z]{4}0[A-Z0-9]{6}$", message = "must be a valid IFSC") String bankIfsc) {
    }

    public record DocumentUploadRequest(
            @NotBlank @Pattern(regexp = "^(PAN|GST_CERTIFICATE|ID_PROOF|ADDRESS_PROOF|BANK_PROOF|OTHER)$")
            String documentType,
            @NotBlank @Pattern(regexp = "^(application/pdf|image/jpeg|image/png)$",
                    message = "must be application/pdf, image/jpeg or image/png")
            String contentType,
            @Positive @jakarta.validation.constraints.Max(value = 10 * 1024 * 1024, message = "must be at most 10 MB")
            long sizeBytes,
            @Size(max = 200) String fileName) {
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
            String bankIfsc,
            String bankAccountLast4,
            Instant createdAt,
            Instant updatedAt) {

        static SellerResponse from(Seller seller) {
            return new SellerResponse(seller.getId(), seller.getUserId(), seller.getBusinessName(),
                    seller.getBusinessEmail(), seller.getBusinessPhone(), seller.getTaxId(),
                    seller.getPickupAddress(), seller.getStatus(), seller.getBankIfsc(),
                    seller.getBankAccountLast4(), seller.getCreatedAt(), seller.getUpdatedAt());
        }
    }

    public record VerificationCheckResponse(SellerVerificationCheck.Type type, SellerVerificationCheck.Outcome outcome,
                                            String detail, String registryName, java.math.BigDecimal matchScore,
                                            Instant checkedAt) {
        static VerificationCheckResponse from(SellerVerificationCheck c) {
            return new VerificationCheckResponse(c.getType(), c.getOutcome(), c.getDetail(), c.getRegistryName(),
                    c.getMatchScore(), c.getCheckedAt());
        }
    }

    /** {@code nextStep} tells the seller's app what to do: connect DigiLocker, wait, or nothing. */
    public record VerificationResponse(UUID id, SellerVerification.Status status, String provider, String nextStep,
                                       int attempts, String outcomeSummary, Instant createdAt, Instant completedAt,
                                       List<VerificationCheckResponse> checks) {
    }

    /** Open the provider's DigiLocker SDK with {@code sessionId}, then call {@code .../identity/complete}. */
    public record IdentitySessionResponse(UUID verificationId, String provider, String sessionId) {
    }

    public record SellerDocumentResponse(UUID id, String documentType, String status, String contentType,
                                         long sizeBytes, String fileName, Instant createdAt, Instant uploadedAt) {
        static SellerDocumentResponse from(SellerDocument d) {
            return new SellerDocumentResponse(d.getId(), d.getDocumentType(), d.getStatus().name(), d.getContentType(),
                    d.getSizeBytes(), d.getOriginalFileName(), d.getCreatedAt(), d.getUploadedAt());
        }
    }

    /** Send the file with {@code method} to {@code url}, including every header in {@code headers}. */
    public record UploadInstructions(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    public record DocumentUploadResponse(SellerDocumentResponse document, UploadInstructions upload) {
    }

    public record DocumentLink(String url, Instant expiresAt) {
    }

    public record StatusHistoryEntry(SellerStatus fromStatus, SellerStatus toStatus, String reason, UUID actorId,
                                     UUID correlationId, Instant changedAt) {
        static StatusHistoryEntry from(SellerStatusHistory history) {
            return new StatusHistoryEntry(history.getFromStatus(), history.getToStatus(), history.getReason(),
                    history.getActorId(), history.getCorrelationId(), history.getChangedAt());
        }
    }

    public record SellerDetailResponse(SellerResponse seller, VerificationResponse verification,
                                       List<SellerDocumentResponse> documents, List<StatusHistoryEntry> history) {
    }

    private SellerDtos() {
    }
}
