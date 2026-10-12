package com.bigbazaar.identity.user;

import com.bigbazaar.identity.common.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "addresses")
public class Address extends Auditable {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    private String label;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String line1;

    private String line2;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column(name = "postal_code", nullable = false)
    private String postalCode;

    @Column(nullable = false)
    private String country;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "delete_reason")
    private String deleteReason;

    protected Address() {
    }

    public Address(UUID userId, String label, String recipient, String phone, String line1, String line2,
                   String city, String state, String postalCode, String country, boolean defaultAddress) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.label = label;
        this.recipient = recipient;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country == null ? "IN" : country;
        this.defaultAddress = defaultAddress;
    }

    public void unsetDefault() {
        defaultAddress = false;
    }

    public void softDelete(UUID actorId, String reason) {
        deletedAt = Instant.now();
        deletedBy = actorId;
        deleteReason = reason;
        defaultAddress = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getPhone() {
        return phone;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public boolean isDefaultAddress() {
        return defaultAddress;
    }
}
