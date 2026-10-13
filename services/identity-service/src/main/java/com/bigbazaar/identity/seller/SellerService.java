package com.bigbazaar.identity.seller;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.CorrelationIds;
import com.bigbazaar.identity.common.ErrorCode;
import com.bigbazaar.identity.common.IdentityEvents;
import com.bigbazaar.identity.kyc.KycProvider;
import com.bigbazaar.identity.mfa.SecretCipher;
import com.bigbazaar.identity.outbox.OutboxWriter;
import com.bigbazaar.identity.seller.SellerDtos.SellerApplicationRequest;
import com.bigbazaar.identity.seller.SellerDtos.SellerDetailResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerDocumentResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerResponse;
import com.bigbazaar.identity.seller.SellerDtos.StatusHistoryEntry;
import com.bigbazaar.identity.seller.SellerDtos.VerificationCheckResponse;
import com.bigbazaar.identity.seller.SellerDtos.VerificationResponse;
import com.bigbazaar.identity.user.Role;
import com.bigbazaar.identity.user.User;
import com.bigbazaar.identity.user.UserService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SellerService {

    private static final Logger log = LoggerFactory.getLogger(SellerService.class);

    private final SellerRepository sellers;
    private final SellerStatusHistoryRepository history;
    private final SellerDocumentRepository documents;
    private final UserService userService;
    private final SellerVerificationRepository verifications;
    private final SellerVerificationCheckRepository checks;
    private final OutboxWriter outbox;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final KycProvider kycProvider;
    private final Clock clock;

    public SellerService(SellerRepository sellers, SellerStatusHistoryRepository history,
                         SellerDocumentRepository documents, SellerVerificationRepository verifications,
                         SellerVerificationCheckRepository checks, UserService userService, OutboxWriter outbox,
                         SecretCipher cipher, JsonMapper json, KycProvider kycProvider, Clock clock) {
        this.sellers = sellers;
        this.history = history;
        this.documents = documents;
        this.verifications = verifications;
        this.checks = checks;
        this.userService = userService;
        this.outbox = outbox;
        this.cipher = cipher;
        this.json = json;
        this.kycProvider = kycProvider;
        this.clock = clock;
    }

    /** FR-SELLER-01: any active customer can apply; a rejected applicant can apply again. */
    @Transactional
    public SellerResponse apply(UUID userId, SellerApplicationRequest request) {
        userService.requireActive(userId);
        var details = new Seller.ApplicationDetails(request.businessName().trim(), request.businessEmail(),
                request.businessPhone(), request.taxId(), request.pickupAddress().trim());
        Seller seller = sellers.findByUserId(userId).orElse(null);
        SellerStatus from = null;
        if (seller == null) {
            seller = sellers.save(Seller.apply(userId, details));
        } else {
            from = seller.getStatus();
            seller.reapply(details);
        }
        var inputs = new KycInputs(request.bankAccountNumber(), request.panDate());
        seller.setKycInputs(request.bankIfsc(), KycInputs.last4(request.bankAccountNumber()),
                inputs.seal(cipher, json, seller.getId()));
        startVerification(seller);
        recordTransition(seller, from, "Application submitted", userId,
                IdentityEvents.SELLER_APPLICATION_SUBMITTED);
        return SellerResponse.from(seller);
    }

    /** Each application gets a fresh automatic verification; an older open one no longer applies. */
    private void startVerification(Seller seller) {
        verifications.findBySellerIdAndStatusIn(seller.getId(), List.of(SellerVerification.Status.AWAITING_IDENTITY,
                        SellerVerification.Status.READY, SellerVerification.Status.RUNNING))
                .forEach(open -> open.supersede(clock.instant()));
        verifications.save(new SellerVerification(seller.getId(), kycProvider.name(), clock.instant()));
    }

    /**
     * A decision made by automatic verification (ADR 0007). Only applies while the application is
     * still waiting; if an admin already acted, their decision stands.
     */
    @Transactional
    public boolean decideAutomatically(UUID sellerId, SellerDecision decision, String reason) {
        Seller seller = findById(sellerId);
        if (seller.getStatus() != SellerStatus.PENDING_VERIFICATION) {
            log.info("Automatic decision skipped sellerId={} status={}", sellerId, seller.getStatus());
            return false;
        }
        SellerStatus from = seller.decide(decision);
        if (seller.getStatus() == SellerStatus.APPROVED) {
            userService.get(seller.getUserId()).grantRole(Role.SELLER);
        }
        recordTransition(seller, from, reason, IdentityEvents.SYSTEM_ACTOR_ID, decision.eventType());
        log.info("Automatic seller decision sellerId={} decision={}", sellerId, decision);
        return true;
    }

    @Transactional(readOnly = true)
    public SellerDetailResponse mine(UUID userId) {
        userService.requireActive(userId);
        return detail(findByUser(userId));
    }

    @Transactional(readOnly = true)
    public Page<SellerResponse> list(SellerStatus status, Pageable pageable) {
        Page<Seller> page = status == null ? sellers.findAll(pageable) : sellers.findByStatus(status, pageable);
        return page.map(SellerResponse::from);
    }

    @Transactional(readOnly = true)
    public SellerDetailResponse get(UUID sellerId) {
        return detail(findById(sellerId));
    }

    /** FR-SELLER-02: admin decision with a mandatory reason; history and event are written atomically. */
    @Transactional
    public SellerResponse decide(UUID sellerId, SellerDecision decision, String reason, UUID actorId) {
        Seller seller = findById(sellerId);
        if (seller.getUserId().equals(actorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Admins cannot decide on their own seller account.");
        }
        SellerStatus from = seller.decide(decision);
        User owner = userService.get(seller.getUserId());
        if (seller.getStatus() == SellerStatus.APPROVED) {
            owner.grantRole(Role.SELLER);
        } else if (from == SellerStatus.APPROVED) {
            owner.revokeRole(Role.SELLER);
        }
        recordTransition(seller, from, reason, actorId, decision.eventType());
        log.info("Seller decision sellerId={} decision={} from={} to={} actorId={}",
                sellerId, decision, from, seller.getStatus(), actorId);
        return SellerResponse.from(seller);
    }

    private void recordTransition(Seller seller, SellerStatus from, String reason, UUID actorId, String eventType) {
        history.save(new SellerStatusHistory(seller.getId(), from, seller.getStatus(), reason, actorId,
                CorrelationIds.current(), clock.instant()));
        outbox.write(Topics.IDENTITY_EVENTS, "Seller", seller.getId().toString(), eventType,
                new IdentityEvents.SellerStatusChanged(seller.getId(), seller.getUserId(), seller.getBusinessName(),
                        from == null ? null : from.name(), seller.getStatus().name(), reason, actorId));
    }

    private SellerDetailResponse detail(Seller seller) {
        return new SellerDetailResponse(SellerResponse.from(seller), verification(seller.getId()),
                documents.findBySellerIdOrderByCreatedAtAsc(seller.getId()).stream()
                        .map(SellerDocumentResponse::from).toList(),
                history.findBySellerIdOrderByChangedAtAscIdAsc(seller.getId()).stream()
                        .map(StatusHistoryEntry::from).toList());
    }

    VerificationResponse verification(UUID sellerId) {
        return verifications.findFirstBySellerIdOrderByCreatedAtDesc(sellerId)
                .map(v -> new VerificationResponse(v.getId(), v.getStatus(), v.getProvider(), nextStep(v),
                        v.getAttempts(), v.getOutcomeSummary(), v.getCreatedAt(), v.getCompletedAt(),
                        checks.findByVerificationIdOrderByIdAsc(v.getId()).stream()
                                .map(VerificationCheckResponse::from).toList()))
                .orElse(null);
    }

    private static String nextStep(SellerVerification v) {
        return switch (v.getStatus()) {
            case AWAITING_IDENTITY -> "CONNECT_DIGILOCKER";
            case READY, RUNNING -> "WAIT";
            case NEEDS_REVIEW -> "WAIT_FOR_ADMIN";
            case PASSED, SUPERSEDED -> "NONE";
        };
    }

    Seller findByUser(UUID userId) {
        return sellers.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SELLER_NOT_FOUND, "No seller application found."));
    }

    Seller findById(UUID sellerId) {
        return sellers.findById(sellerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SELLER_NOT_FOUND, "Seller not found."));
    }
}
