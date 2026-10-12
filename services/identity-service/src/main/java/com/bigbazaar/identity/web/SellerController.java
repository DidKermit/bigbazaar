package com.bigbazaar.identity.web;

import com.bigbazaar.identity.seller.SellerDtos.SellerApplicationRequest;
import com.bigbazaar.identity.seller.SellerDtos.SellerDetailResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerDocumentRequest;
import com.bigbazaar.identity.seller.SellerDtos.SellerDocumentResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerResponse;
import com.bigbazaar.identity.seller.SellerService;
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
@RequestMapping("/api/v1/sellers")
@Tag(name = "Seller onboarding")
public class SellerController {

    private final SellerService sellerService;

    public SellerController(SellerService sellerService) {
        this.sellerService = sellerService;
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Apply to become a seller, or re-apply after a rejection")
    public SellerResponse apply(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SellerApplicationRequest request) {
        return sellerService.apply(CurrentUser.id(jwt), request);
    }

    @GetMapping("/me")
    @Operation(summary = "My seller application, documents and status history")
    public SellerDetailResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return sellerService.mine(CurrentUser.id(jwt));
    }

    @PostMapping("/me/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach metadata for a verification document already uploaded to object storage")
    public SellerDocumentResponse addDocument(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody SellerDocumentRequest request) {
        return sellerService.addDocument(CurrentUser.id(jwt), request);
    }
}
