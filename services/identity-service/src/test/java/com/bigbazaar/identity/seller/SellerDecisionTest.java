package com.bigbazaar.identity.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerDecisionTest {

    private static Seller newApplication() {
        return Seller.apply(UUID.randomUUID(), new Seller.ApplicationDetails("Acme Traders", "acme@example.com",
                "9876543210", null, "12 MG Road, Bengaluru"));
    }

    @Test
    void newApplicationIsPendingVerification() {
        assertThat(newApplication().getStatus()).isEqualTo(SellerStatus.PENDING_VERIFICATION);
    }

    @Test
    void approveFromPendingAndFromUnderReview() {
        Seller direct = newApplication();
        assertThat(direct.decide(SellerDecision.APPROVE)).isEqualTo(SellerStatus.PENDING_VERIFICATION);
        assertThat(direct.getStatus()).isEqualTo(SellerStatus.APPROVED);

        Seller reviewed = newApplication();
        reviewed.decide(SellerDecision.START_REVIEW);
        assertThat(reviewed.decide(SellerDecision.APPROVE)).isEqualTo(SellerStatus.UNDER_REVIEW);
    }

    @Test
    void suspendAndReinstateOnlyApplyToApprovedSellers() {
        Seller seller = newApplication();
        assertThatThrownBy(() -> seller.decide(SellerDecision.SUSPEND))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

        seller.decide(SellerDecision.APPROVE);
        seller.decide(SellerDecision.SUSPEND);
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.SUSPENDED);
        seller.decide(SellerDecision.REINSTATE);
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.APPROVED);
    }

    @Test
    void rejectedSellerCanReapplyButPendingCannot() {
        Seller seller = newApplication();
        var details = new Seller.ApplicationDetails("Acme Traders Pvt", "acme@example.com", "9876543210",
                null, "12 MG Road, Bengaluru");
        assertThatThrownBy(() -> seller.reapply(details)).isInstanceOf(BusinessException.class);

        seller.decide(SellerDecision.REJECT);
        seller.reapply(details);
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING_VERIFICATION);
        assertThat(seller.getBusinessName()).isEqualTo("Acme Traders Pvt");
    }

    @Test
    void approvedSellerCannotBeRejected() {
        Seller seller = newApplication();
        seller.decide(SellerDecision.APPROVE);
        assertThatThrownBy(() -> seller.decide(SellerDecision.REJECT)).isInstanceOf(BusinessException.class);
    }
}
