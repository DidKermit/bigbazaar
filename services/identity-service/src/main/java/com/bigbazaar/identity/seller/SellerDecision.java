package com.bigbazaar.identity.seller;

import com.bigbazaar.identity.common.IdentityEvents;
import java.util.Set;

/** Admin actions on a seller, with the statuses each may start from (FR-SELLER-02). */
public enum SellerDecision {
    START_REVIEW(SellerStatus.UNDER_REVIEW, IdentityEvents.SELLER_REVIEW_STARTED,
            Set.of(SellerStatus.PENDING_VERIFICATION)),
    APPROVE(SellerStatus.APPROVED, IdentityEvents.SELLER_APPROVED,
            Set.of(SellerStatus.PENDING_VERIFICATION, SellerStatus.UNDER_REVIEW)),
    REJECT(SellerStatus.REJECTED, IdentityEvents.SELLER_REJECTED,
            Set.of(SellerStatus.PENDING_VERIFICATION, SellerStatus.UNDER_REVIEW)),
    SUSPEND(SellerStatus.SUSPENDED, IdentityEvents.SELLER_SUSPENDED,
            Set.of(SellerStatus.APPROVED)),
    REINSTATE(SellerStatus.APPROVED, IdentityEvents.SELLER_REINSTATED,
            Set.of(SellerStatus.SUSPENDED));

    private final SellerStatus target;
    private final String eventType;
    private final Set<SellerStatus> allowedFrom;

    SellerDecision(SellerStatus target, String eventType, Set<SellerStatus> allowedFrom) {
        this.target = target;
        this.eventType = eventType;
        this.allowedFrom = allowedFrom;
    }

    public SellerStatus target() {
        return target;
    }

    public String eventType() {
        return eventType;
    }

    public boolean isAllowedFrom(SellerStatus current) {
        return allowedFrom.contains(current);
    }
}
