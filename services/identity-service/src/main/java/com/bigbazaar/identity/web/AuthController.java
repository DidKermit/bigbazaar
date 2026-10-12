package com.bigbazaar.identity.web;

import com.bigbazaar.identity.auth.AuthDtos.LoginRequest;
import com.bigbazaar.identity.auth.AuthDtos.LoginResponse;
import com.bigbazaar.identity.auth.AuthDtos.MfaVerifyRequest;
import com.bigbazaar.identity.auth.AuthDtos.RefreshRequest;
import com.bigbazaar.identity.auth.AuthDtos.RegisterRequest;
import com.bigbazaar.identity.auth.AuthDtos.RegisteredUser;
import com.bigbazaar.identity.auth.AuthDtos.TokenResponse;
import com.bigbazaar.identity.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a customer account")
    public RegisteredUser register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Check email and password; returns tokens, or an MFA challenge when MFA is on")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/mfa/verify")
    @Operation(summary = "Complete an MFA login with an authenticator code or a one-time recovery code")
    public TokenResponse verifyMfa(@Valid @RequestBody MfaVerifyRequest request) {
        return authService.verifyMfa(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token; the presented token is invalidated")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the refresh token and its rotation chain")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }
}
