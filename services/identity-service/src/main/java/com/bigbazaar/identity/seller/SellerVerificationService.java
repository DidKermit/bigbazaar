package com.bigbazaar.identity.seller;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import com.bigbazaar.identity.common.IdentityEvents;
import com.bigbazaar.identity.kyc.KycProperties;
import com.bigbazaar.identity.kyc.KycProvider;
import com.bigbazaar.identity.kyc.KycUnavailableException;
import com.bigbazaar.identity.mfa.SecretCipher;
import com.bigbazaar.identity.outbox.OutboxWriter;
import com.bigbazaar.identity.seller.SellerDtos.IdentitySessionResponse;
import com.bigbazaar.identity.seller.SellerDtos.VerificationResponse;
import com.bigbazaar.identity.seller.SellerVerificationCheck.CheckResult;
import com.bigbazaar.identity.seller.SellerVerificationCheck.Outcome;
import com.bigbazaar.identity.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Automatic seller verification (ADR 0007). The seller connects DigiLocker; a worker then runs the
 * registry checks through the {@link KycProvider} and either approves the seller or hands the
 * application to an admin with the results. Provider calls happen outside database transactions, so
 * a slow registry never holds locks.
 */
@Service
public class SellerVerificationService {

    private static final Logger log = LoggerFactory.getLogger(SellerVerificationService.class);
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final SellerVerificationRepository verifications;
    private final SellerVerificationCheckRepository checks;
    private final SellerService sellerService;
    private final UserService userService;
    private final VerificationRules rules;
    private final KycProvider provider;
    private final KycProperties properties;
    private final OutboxWriter outbox;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SellerVerificationService(SellerVerificationRepository verifications,
                                     SellerVerificationCheckRepository checks, SellerService sellerService,
                                     UserService userService, KycProvider provider, KycProperties properties,
                                     OutboxWriter outbox, SecretCipher cipher, JsonMapper json,
                                     TransactionTemplate tx, Clock clock) {
        this.verifications = verifications;
        this.checks = checks;
        this.sellerService = sellerService;
        this.userService = userService;
        this.rules = new VerificationRules(provider, properties.nameMatchScore());
        this.provider = provider;
        this.properties = properties;
        this.outbox = outbox;
        this.cipher = cipher;
        this.json = json;
        this.tx = tx;
        this.clock = clock;
    }

    /** Step 1 for the seller: a DigiLocker session their app opens with the provider's SDK. */
    public IdentitySessionResponse startIdentity(UUID userId) {
        UUID verificationId = tx.execute(s -> awaitingIdentity(userId).getId());
        String sessionId = provider.startIdentitySession().sessionId();
        tx.executeWithoutResult(s -> {
            SellerVerification v = verifications.findById(verificationId).orElseThrow();
            if (v.getStatus() != SellerVerification.Status.AWAITING_IDENTITY) {
                throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION, "Verification already moved on.");
            }
            v.identityStarted(sessionId);
        });
        return new IdentitySessionResponse(verificationId, provider.name(), sessionId);
    }

    /** Step 2: the seller finished DigiLocker; the checks run shortly after. */
    @Transactional
    public VerificationResponse completeIdentity(UUID userId) {
        SellerVerification v = awaitingIdentity(userId);
        if (v.getIdentitySessionId() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION, "Start DigiLocker first.");
        }
        v.ready(clock.instant());
        return sellerService.verification(v.getSellerId());
    }

    private SellerVerification awaitingIdentity(UUID userId) {
        userService.requireActive(userId);
        Seller seller = sellerService.findByUser(userId);
        return verifications.findFirstBySellerIdOrderByCreatedAtDesc(seller.getId())
                .filter(v -> v.getStatus() == SellerVerification.Status.AWAITING_IDENTITY
                        && seller.getStatus() == SellerStatus.PENDING_VERIFICATION)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                        "There is no verification waiting for DigiLocker."));
    }

    /** Called by the worker: claim due attempts, run each, record the outcome. */
    public int processDue(int batchSize) {
        List<Job> jobs = tx.execute(s -> verifications.lockDue(clock.instant(), batchSize).stream()
                .map(this::claim).toList());
        jobs.forEach(this::run);
        return jobs.size();
    }

    private Job claim(SellerVerification v) {
        v.claim(clock.instant(), clock.instant().plus(LEASE));
        Seller seller = sellerService.findById(v.getSellerId());
        KycInputs inputs = KycInputs.open(seller.getKycInputsEncrypted(), cipher, json, seller.getId());
        var applicant = new VerificationRules.Applicant(seller.getTaxId(), inputs.panDate(), seller.getBankIfsc(),
                inputs.bankAccountNumber(), v.getIdentitySessionId(), userService.get(seller.getUserId()).getName(),
                seller.getBusinessName());
        return new Job(v.getId(), seller.getId(), v.getAttempts(), applicant);
    }

    private void run(Job job) {
        List<CheckResult> results;
        try {
            results = rules.check(job.applicant());
        } catch (KycUnavailableException e) {
            log.warn("KYC provider unavailable verificationId={} attempt={}: {}", job.verificationId(),
                    job.attempt(), e.getMessage());
            if (job.attempt() < properties.maxAttempts()) {
                tx.executeWithoutResult(s -> verifications.findById(job.verificationId()).orElseThrow()
                        .retryAt(clock.instant().plus(properties.retryDelay().multipliedBy(job.attempt()))));
                return;
            }
            results = List.of(CheckResult.of(SellerVerificationCheck.Type.IDENTITY, Outcome.ERROR,
                    "Verification service unavailable after " + job.attempt() + " attempts: " + e.getMessage()));
        } catch (RuntimeException e) {
            // A bug or an unexpected reply must not loop forever: hand it to a person.
            log.error("Verification failed verificationId={}", job.verificationId(), e);
            results = List.of(CheckResult.of(SellerVerificationCheck.Type.IDENTITY, Outcome.ERROR,
                    "Automatic verification failed unexpectedly; please review manually."));
        }
        List<CheckResult> finalResults = results;
        tx.executeWithoutResult(s -> finish(job, finalResults));
    }

    private void finish(Job job, List<CheckResult> results) {
        SellerVerification v = verifications.findById(job.verificationId()).orElseThrow();
        if (v.getStatus() != SellerVerification.Status.RUNNING) {
            return; // superseded by a re-application while the checks ran
        }
        results.forEach(r -> checks.save(new SellerVerificationCheck(v.getId(), r, clock.instant())));
        boolean passed = results.stream().allMatch(r -> r.outcome() == Outcome.PASS);
        String reason = passed
                ? "Automatic verification passed: GSTIN active; PAN, bank account and DigiLocker identity match"
                : "Automatic verification needs review: " + results.stream()
                        .filter(r -> r.outcome() != Outcome.PASS).map(CheckResult::detail)
                        .collect(Collectors.joining("; "));
        reason = reason.length() > 500 ? reason.substring(0, 497) + "..." : reason;
        v.complete(passed, reason, clock.instant());
        sellerService.decideAutomatically(job.sellerId(),
                passed ? SellerDecision.APPROVE : SellerDecision.START_REVIEW, reason);
        outbox.write(Topics.IDENTITY_EVENTS, "Seller", job.sellerId().toString(),
                IdentityEvents.SELLER_VERIFICATION_COMPLETED,
                new IdentityEvents.SellerVerificationCompleted(job.sellerId(), v.getId(), v.getProvider(),
                        passed ? "PASSED" : "NEEDS_REVIEW",
                        new ArrayList<>(results.stream().map(r -> new IdentityEvents.CheckOutcome(r.type().name(),
                                r.outcome().name())).toList()),
                        reason, IdentityEvents.SYSTEM_ACTOR_ID));
        log.info("Seller verification finished sellerId={} verificationId={} outcome={}", job.sellerId(),
                v.getId(), passed ? "PASSED" : "NEEDS_REVIEW");
    }

    private record Job(UUID verificationId, UUID sellerId, int attempt, VerificationRules.Applicant applicant) {
    }
}
