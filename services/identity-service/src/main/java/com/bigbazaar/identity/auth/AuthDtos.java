package com.bigbazaar.identity.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optional leading +")
            String phone,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain a letter and a digit")
            String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(
            String tokenType,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt) {
    }

    public record MfaVerifyRequest(
            @NotBlank String mfaToken,
            @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code,
            @Size(max = 24) String recoveryCode) {
    }

    /**
     * Either tokens ({@code status = AUTHENTICATED}) or a challenge to complete with
     * {@code POST /auth/mfa/verify} ({@code status = MFA_REQUIRED}).
     * {@code mfaEnrollmentRequired} tells admins and support staff to set up MFA before their
     * privileged actions work.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoginResponse(
            String status,
            String tokenType,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            String mfaToken,
            Instant mfaTokenExpiresAt,
            Boolean mfaEnrollmentRequired) {

        static LoginResponse authenticated(TokenResponse t, boolean enrollmentRequired) {
            return new LoginResponse("AUTHENTICATED", t.tokenType(), t.accessToken(), t.accessTokenExpiresAt(),
                    t.refreshToken(), t.refreshTokenExpiresAt(), null, null, enrollmentRequired ? Boolean.TRUE : null);
        }

        static LoginResponse mfaRequired(String mfaToken, Instant expiresAt) {
            return new LoginResponse("MFA_REQUIRED", null, null, null, null, null, mfaToken, expiresAt, null);
        }
    }

    public record RegisteredUser(UUID id, String name, String email, List<String> roles) {
    }

    private AuthDtos() {
    }
}
