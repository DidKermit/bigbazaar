package com.bigbazaar.identity.seller;

import com.bigbazaar.identity.kyc.KycProvider;
import com.bigbazaar.identity.kyc.NameMatcher;
import com.bigbazaar.identity.seller.SellerVerificationCheck.CheckResult;
import com.bigbazaar.identity.seller.SellerVerificationCheck.Outcome;
import com.bigbazaar.identity.seller.SellerVerificationCheck.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The four checks and what counts as a pass. The GST registration's legal name is the anchor: the PAN
 * must belong to it, the bank account must be held by it, and the DigiLocker identity must be that
 * person or the account holder on BigBazaar.
 */
class VerificationRules {

    private final KycProvider provider;
    private final double threshold;

    VerificationRules(KycProvider provider, double threshold) {
        this.provider = provider;
        this.threshold = threshold;
    }

    /** What the applicant gave us. {@code panDate} is DD/MM/YYYY: birth, or incorporation for a company. */
    record Applicant(String gstin, String panDate, String bankIfsc, String bankAccountNumber,
                     String identitySessionId, String accountName, String businessName) {
    }

    /** Runs every check; a provider outage aborts the whole run so it can be retried. */
    List<CheckResult> check(Applicant a) {
        List<CheckResult> results = new ArrayList<>();
        String legalName = null;
        if (a.gstin() == null) {
            results.add(CheckResult.of(Type.GSTIN, Outcome.MISSING, "No GSTIN given"));
        } else {
            KycProvider.GstinResult gst = provider.verifyGstin(a.gstin());
            if (!gst.valid()) {
                results.add(new CheckResult(Type.GSTIN, Outcome.FAIL, "GSTIN not found in the GST registry",
                        null, null, gst.reference()));
            } else if (!gst.active()) {
                results.add(new CheckResult(Type.GSTIN, Outcome.FAIL, "GSTIN status is " + gst.status(),
                        gst.legalName(), null, gst.reference()));
            } else {
                legalName = gst.legalName();
                results.add(new CheckResult(Type.GSTIN, Outcome.PASS, "GSTIN active", legalName, null,
                        gst.reference()));
            }
        }
        results.add(pan(a, legalName));
        results.add(bank(a, legalName));
        results.add(identity(a, legalName));
        return results;
    }

    private CheckResult pan(Applicant a, String legalName) {
        if (legalName == null) {
            return CheckResult.of(Type.PAN, Outcome.MISSING, "PAN not checked: needs an active GSTIN");
        }
        if (a.panDate() == null) {
            return CheckResult.of(Type.PAN, Outcome.MISSING, "Date on PAN not given");
        }
        // Characters 3-12 of a GSTIN are the holder's PAN.
        KycProvider.PanResult pan = provider.verifyPan(a.gstin().substring(2, 12), legalName, a.panDate());
        if (!pan.valid()) {
            String why = pan.remarks() == null ? pan.status() : pan.status() + " (" + pan.remarks() + ")";
            return new CheckResult(Type.PAN, Outcome.FAIL, "PAN is " + why, null, null, pan.reference());
        }
        if (!pan.nameMatches()) {
            return new CheckResult(Type.PAN, Outcome.MISMATCH, "Name on PAN doesn't match the GST legal name",
                    null, null, pan.reference());
        }
        if (!pan.dateMatches()) {
            return new CheckResult(Type.PAN, Outcome.MISMATCH, "Date on PAN doesn't match", null, null,
                    pan.reference());
        }
        return new CheckResult(Type.PAN, Outcome.PASS, "PAN valid and matches the GST legal name", null, null,
                pan.reference());
    }

    private CheckResult bank(Applicant a, String legalName) {
        if (a.bankAccountNumber() == null || a.bankIfsc() == null) {
            return CheckResult.of(Type.BANK_ACCOUNT, Outcome.MISSING, "Bank account not given");
        }
        KycProvider.BankResult bank = provider.verifyBankAccount(a.bankIfsc(), a.bankAccountNumber());
        if (!bank.accountExists()) {
            return new CheckResult(Type.BANK_ACCOUNT, Outcome.FAIL, "Bank account not verified: " + bank.message(),
                    null, null, bank.reference());
        }
        double score = best(bank.nameAtBank(), legalName, a.businessName(), a.accountName());
        Outcome outcome = score >= threshold ? Outcome.PASS : Outcome.MISMATCH;
        return new CheckResult(Type.BANK_ACCOUNT, outcome, outcome == Outcome.PASS
                ? "Account holder matches" : "Bank account is held by a different name",
                bank.nameAtBank(), score, bank.reference());
    }

    private CheckResult identity(Applicant a, String legalName) {
        if (a.identitySessionId() == null) {
            return CheckResult.of(Type.IDENTITY, Outcome.MISSING, "DigiLocker not connected");
        }
        KycProvider.IdentityResult id = provider.fetchIdentity(a.identitySessionId());
        if (!id.completed()) {
            return new CheckResult(Type.IDENTITY, Outcome.FAIL,
                    "DigiLocker consent not completed (" + id.sessionStatus() + ")", null, null, id.reference());
        }
        double score = best(id.name(), legalName, a.accountName());
        Outcome outcome = score >= threshold ? Outcome.PASS : Outcome.MISMATCH;
        return new CheckResult(Type.IDENTITY, outcome, outcome == Outcome.PASS
                ? "Aadhaar name matches" : "Aadhaar name matches neither the GST legal name nor the account name",
                id.name(), score, id.reference());
    }

    private static double best(String name, String... candidates) {
        return Stream.of(candidates).filter(c -> c != null && !c.isBlank())
                .mapToDouble(c -> NameMatcher.score(name, c)).max().orElse(0);
    }
}
