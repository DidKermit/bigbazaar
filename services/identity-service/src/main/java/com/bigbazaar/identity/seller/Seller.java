package com.bigbazaar.identity.seller;

import com.bigbazaar.identity.common.Auditable;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "sellers")
public class Seller extends Auditable {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "business_email", nullable = false)
    private String businessEmail;

    @Column(name = "business_phone", nullable = false)
    private String businessPhone;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "pickup_address", nullable = false)
    private String pickupAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SellerStatus status;

    @Version
    private long version;

    protected Seller() {
    }

    static Seller apply(UUID userId, ApplicationDetails details) {
        Seller seller = new Seller();
        seller.id = UUID.randomUUID();
        seller.userId = userId;
        seller.status = SellerStatus.PENDING_VERIFICATION;
        seller.update(details);
        return seller;
    }

    /** A rejected applicant may fix their details and apply again. */
    void reapply(ApplicationDetails details) {
        if (status != SellerStatus.REJECTED) {
            throw new BusinessException(ErrorCode.SELLER_APPLICATION_EXISTS,
                    "A seller application already exists with status " + status + ".");
        }
        update(details);
        status = SellerStatus.PENDING_VERIFICATION;
    }

    SellerStatus decide(SellerDecision decision) {
        if (!decision.isAllowedFrom(status)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot " + decision + " a seller in status " + status + ".");
        }
        SellerStatus previous = status;
        status = decision.target();
        return previous;
    }

    private void update(ApplicationDetails details) {
        this.businessName = details.businessName();
        this.businessEmail = details.businessEmail();
        this.businessPhone = details.businessPhone();
        this.taxId = details.taxId();
        this.pickupAddress = details.pickupAddress();
    }

    record ApplicationDetails(String businessName, String businessEmail, String businessPhone, String taxId,
                              String pickupAddress) {
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getBusinessEmail() {
        return businessEmail;
    }

    public String getBusinessPhone() {
        return businessPhone;
    }

    public String getTaxId() {
        return taxId;
    }

    public String getPickupAddress() {
        return pickupAddress;
    }

    public SellerStatus getStatus() {
        return status;
    }
}
