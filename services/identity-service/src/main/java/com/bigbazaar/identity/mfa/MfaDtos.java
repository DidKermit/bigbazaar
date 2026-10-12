package com.bigbazaar.identity.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class MfaDtos {

    public record PasswordConfirmation(@NotBlank String password) {
    }

    public record TotpCode(@NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {
    }

    public record DisableRequest(
            @NotBlank String password,
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {
    }

    public record AdminResetRequest(@NotBlank @Size(min = 5, max = 500) String reason) {
    }

    /** {@code otpauthUri} is what the QR code encodes; {@code secret} is for manual entry. */
    public record TotpSetupResponse(String secret, String otpauthUri, int digits, int periodSeconds) {
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes, String notice) {
    }

    public record MfaStatusResponse(boolean enabled, String method, Instant enabledAt, long recoveryCodesRemaining) {
    }

    private MfaDtos() {
    }
}
