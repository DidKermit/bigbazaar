package com.bigbazaar.identity.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only result of one registry check; the database rejects UPDATE and DELETE. */
@Entity
@Immutable
@Table(name = "seller_verification_checks")
public class SellerVerificationCheck {

    public enum Type { GSTIN, PAN, BANK_ACCOUNT, IDENTITY }

    /** MISSING: the applicant didn't give what the check needs. ERROR: the provider never answered. */
    public enum Outcome { PASS, FAIL, MISMATCH, MISSING, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "verification_id", nullable = false)
    private UUID verificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    @Column(nullable = false)
    private String detail;

    @Column(name = "registry_name")
    private String registryName;

    @Column(name = "match_score")
    private BigDecimal matchScore;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected SellerVerificationCheck() {
    }

    SellerVerificationCheck(UUID verificationId, CheckResult result, Instant checkedAt) {
        this.verificationId = verificationId;
        this.type = result.type();
        this.outcome = result.outcome();
        this.detail = result.detail().length() > 500 ? result.detail().substring(0, 500) : result.detail();
        this.registryName = result.registryName();
        this.matchScore = result.matchScore() == null ? null
                : BigDecimal.valueOf(result.matchScore()).setScale(3, RoundingMode.HALF_UP);
        this.providerRef = result.providerRef();
        this.checkedAt = checkedAt;
    }

    /** What one check found, before it is stored. */
    public record CheckResult(Type type, Outcome outcome, String detail, String registryName, Double matchScore,
                              String providerRef) {
        static CheckResult of(Type type, Outcome outcome, String detail) {
            return new CheckResult(type, outcome, detail, null, null, null);
        }
    }

    public Type getType() {
        return type;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    public String getRegistryName() {
        return registryName;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
