package com.bigbazaar.identity.kyc;

/**
 * Government-registry checks for seller onboarding, behind one interface so the provider can change
 * (ADR 0007). Implementations return what the registry says; deciding whether it is good enough is
 * {@link com.bigbazaar.identity.seller.SellerVerificationService}'s job. They throw
 * {@link KycUnavailableException} for anything worth retrying.
 */
public interface KycProvider {

    String name();

    GstinResult verifyGstin(String gstin);

    /** The registry compares the name and date we send; nothing about the holder is returned. */
    PanResult verifyPan(String pan, String nameAsPerPan, String dateOfBirthDdMmYyyy);

    /** Penny drop: ₹1 is deposited and the bank returns the account holder's name. */
    BankResult verifyBankAccount(String ifsc, String accountNumber);

    /** Starts a DigiLocker consent session; the seller's app opens it with the provider's SDK. */
    IdentitySession startIdentitySession();

    /** Reads the Aadhaar record the seller shared, once the session has succeeded. */
    IdentityResult fetchIdentity(String sessionId);

    record GstinResult(boolean valid, String status, String legalName, String reference) {
        public boolean active() {
            return valid && "Active".equalsIgnoreCase(status);
        }
    }

    record PanResult(String status, boolean nameMatches, boolean dateMatches, String category, String remarks,
                     String reference) {
        public boolean valid() {
            return "valid".equalsIgnoreCase(status);
        }
    }

    record BankResult(boolean accountExists, String nameAtBank, String message, String reference) {
    }

    record IdentitySession(String sessionId) {
    }

    /** {@code completed} is false when the seller hasn't finished consent, or the session failed or expired. */
    record IdentityResult(boolean completed, String sessionStatus, String name, String reference) {
    }
}
