package com.bigbazaar.identity.auth;

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

    public record RegisteredUser(UUID id, String name, String email, List<String> roles) {
    }

    private AuthDtos() {
    }
}
