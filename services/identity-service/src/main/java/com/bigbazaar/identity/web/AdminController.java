package com.bigbazaar.identity.web;

import com.bigbazaar.identity.mfa.MfaDtos.AdminResetRequest;
import com.bigbazaar.identity.mfa.MfaService;
import com.bigbazaar.identity.seller.SellerDtos.SellerDecisionRequest;
import com.bigbazaar.identity.seller.SellerDtos.SellerDetailResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerResponse;
import com.bigbazaar.identity.seller.SellerService;
import com.bigbazaar.identity.seller.SellerStatus;
import com.bigbazaar.identity.user.UserDtos.StatusChangeRequest;
import com.bigbazaar.identity.user.UserDtos.UserProfile;
import com.bigbazaar.identity.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('AMR_MFA')")
@Tag(name = "Admin")
public class AdminController {

    private final SellerService sellerService;
    private final UserService userService;
    private final MfaService mfaService;

    public AdminController(SellerService sellerService, UserService userService, MfaService mfaService) {
        this.sellerService = sellerService;
        this.userService = userService;
        this.mfaService = mfaService;
    }

    @GetMapping("/sellers")
    @Operation(summary = "List seller applications, optionally by status")
    public Page<SellerResponse> sellers(@RequestParam(required = false) SellerStatus status,
                                        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC)
                                        Pageable pageable) {
        return sellerService.list(status, pageable);
    }

    @GetMapping("/sellers/{sellerId}")
    public SellerDetailResponse seller(@PathVariable UUID sellerId) {
        return sellerService.get(sellerId);
    }

    @PostMapping("/sellers/{sellerId}/decisions")
    @Operation(summary = "Start review, approve, reject, suspend or reinstate a seller; reason is required")
    public SellerResponse decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sellerId,
                                 @Valid @RequestBody SellerDecisionRequest request) {
        UUID adminId = CurrentUser.id(jwt);
        userService.requireActive(adminId);
        return sellerService.decide(sellerId, request.decision(), request.reason().trim(), adminId);
    }

    @PostMapping("/users/{userId}/suspend")
    public UserProfile suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                               @Valid @RequestBody StatusChangeRequest request) {
        UUID adminId = CurrentUser.id(jwt);
        userService.requireActive(adminId);
        return userService.suspend(userId, request.reason().trim(), adminId);
    }

    @PostMapping("/users/{userId}/reactivate")
    public UserProfile reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                  @Valid @RequestBody StatusChangeRequest request) {
        UUID adminId = CurrentUser.id(jwt);
        userService.requireActive(adminId);
        return userService.reactivate(userId, request.reason().trim(), adminId);
    }

    @PostMapping("/users/{userId}/mfa/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a user's MFA after identity checks, e.g. lost phone and recovery codes")
    public void resetMfa(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                         @Valid @RequestBody AdminResetRequest request) {
        UUID adminId = CurrentUser.id(jwt);
        userService.requireActive(adminId);
        mfaService.adminReset(userId, request.reason().trim(), adminId);
    }
}
