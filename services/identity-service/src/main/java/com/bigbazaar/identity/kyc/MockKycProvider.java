package com.bigbazaar.identity.kyc;

import java.util.UUID;

/**
 * Stand-in provider for development and tests: no keys, no network, predictable answers. Every
 * registry knows the same person, {@value #REGISTERED_NAME}, so a normal application passes. Specific
 * test values trigger each failure (also listed in the identity-service README):
 *
 * <ul>
 *   <li>GSTIN with state code {@code 99}: registration cancelled</li>
 *   <li>PAN (characters 3-12 of the GSTIN) ending in {@code X}: name on PAN doesn't match</li>
 *   <li>Bank account ending {@code 0000}: account doesn't exist</li>
 *   <li>Bank account ending {@code 1111}: held by someone else</li>
 *   <li>Bank account ending {@code 5030}: bank network unavailable (exercises retries)</li>
 * </ul>
 */
public class MockKycProvider implements KycProvider {

    public static final String REGISTERED_NAME = "KAI SHARMA";

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public GstinResult verifyGstin(String gstin) {
        boolean cancelled = gstin.startsWith("99");
        return new GstinResult(true, cancelled ? "Cancelled" : "Active", REGISTERED_NAME, ref());
    }

    @Override
    public PanResult verifyPan(String pan, String nameAsPerPan, String dateOfBirthDdMmYyyy) {
        boolean nameMatches = !pan.endsWith("X") && NameMatcher.matches(nameAsPerPan, REGISTERED_NAME, 0.8);
        return new PanResult("valid", nameMatches, true, "individual", null, ref());
    }

    @Override
    public BankResult verifyBankAccount(String ifsc, String accountNumber) {
        if (accountNumber.endsWith("5030")) {
            throw new KycUnavailableException("Mock bank network unavailable");
        }
        if (accountNumber.endsWith("0000")) {
            return new BankResult(false, null, "Account does not exist", ref());
        }
        String holder = accountNumber.endsWith("1111") ? "SOMEONE ELSE" : REGISTERED_NAME;
        return new BankResult(true, holder, "Bank Account details verified successfully.", ref());
    }

    @Override
    public IdentitySession startIdentitySession() {
        return new IdentitySession("mock-" + UUID.randomUUID());
    }

    @Override
    public IdentityResult fetchIdentity(String sessionId) {
        return new IdentityResult(true, "succeeded", REGISTERED_NAME, ref());
    }

    private static String ref() {
        return "mock-" + UUID.randomUUID();
    }
}
