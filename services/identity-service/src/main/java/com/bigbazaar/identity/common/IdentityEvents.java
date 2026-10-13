package com.bigbazaar.identity.common;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads published on the identity-events topic. Additive changes only; bump the version otherwise. */
public final class IdentityEvents {

    public static final String USER_REGISTERED = "UserRegistered";
    public static final String USER_SUSPENDED = "UserSuspended";
    public static final String USER_REACTIVATED = "UserReactivated";
    public static final String MFA_ENABLED = "MfaEnabled";
    public static final String MFA_DISABLED = "MfaDisabled";
    public static final String MFA_RESET = "MfaReset";
    public static final String SELLER_DOCUMENT_UPLOADED = "SellerDocumentUploaded";
    public static final String SELLER_APPLICATION_SUBMITTED = "SellerApplicationSubmitted";
    public static final String SELLER_REVIEW_STARTED = "SellerReviewStarted";
    public static final String SELLER_APPROVED = "SellerApproved";
    public static final String SELLER_REJECTED = "SellerRejected";
    public static final String SELLER_SUSPENDED = "SellerSuspended";
    public static final String SELLER_REINSTATED = "SellerReinstated";
    public static final String SELLER_VERIFICATION_COMPLETED = "SellerVerificationCompleted";

    /** actorId on decisions made by automatic seller verification rather than a person. */
    public static final UUID SYSTEM_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000a070");

    public record UserRegistered(UUID userId, String name, String email, List<String> roles, Instant registeredAt) {
    }

    public record UserStatusChanged(UUID userId, String fromStatus, String toStatus, String reason, UUID actorId) {
    }

    public record MfaChanged(UUID userId, String method, String reason, UUID actorId) {
    }

    public record SellerDocumentUploaded(UUID sellerId, UUID documentId, String documentType, String contentType,
                                         long sizeBytes, UUID actorId) {
    }

    public record SellerStatusChanged(UUID sellerId, UUID userId, String businessName, String fromStatus,
                                      String toStatus, String reason, UUID actorId) {
    }

    /** Outcomes only: no identifiers or registry names leave identity-service. */
    public record SellerVerificationCompleted(UUID sellerId, UUID verificationId, String provider, String outcome,
                                              List<CheckOutcome> checks, String reason, UUID actorId) {
    }

    public record CheckOutcome(String type, String outcome) {
    }

    private IdentityEvents() {
    }
}
