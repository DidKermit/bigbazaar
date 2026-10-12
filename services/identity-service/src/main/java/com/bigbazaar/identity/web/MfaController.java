package com.bigbazaar.identity.web;

import com.bigbazaar.identity.mfa.MfaDtos.DisableRequest;
import com.bigbazaar.identity.mfa.MfaDtos.MfaStatusResponse;
import com.bigbazaar.identity.mfa.MfaDtos.PasswordConfirmation;
import com.bigbazaar.identity.mfa.MfaDtos.RecoveryCodesResponse;
import com.bigbazaar.identity.mfa.MfaDtos.TotpCode;
import com.bigbazaar.identity.mfa.MfaDtos.TotpSetupResponse;
import com.bigbazaar.identity.mfa.MfaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/mfa")
@Tag(name = "Multi-factor authentication")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @GetMapping
    public MfaStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return mfaService.status(CurrentUser.id(jwt));
    }

    @PostMapping("/totp/setup")
    @Operation(summary = "Start authenticator-app setup; returns the secret and otpauth:// URI for the QR code")
    public TotpSetupResponse setup(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordConfirmation request) {
        return mfaService.startTotpSetup(CurrentUser.id(jwt), request.password());
    }

    @PostMapping("/totp/activate")
    @Operation(summary = "Confirm the first code from the app; turns MFA on and returns recovery codes once")
    public RecoveryCodesResponse activate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TotpCode request) {
        return mfaService.activateTotp(CurrentUser.id(jwt), request.code());
    }

    @PostMapping("/recovery-codes")
    @Operation(summary = "Replace all recovery codes; needs a current authenticator code")
    public RecoveryCodesResponse regenerate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TotpCode request) {
        return mfaService.regenerateRecoveryCodes(CurrentUser.id(jwt), request.code());
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn MFA off; needs password and a current code, and signs out every session")
    public void disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DisableRequest request) {
        mfaService.disable(CurrentUser.id(jwt), request.password(), request.code());
    }
}
