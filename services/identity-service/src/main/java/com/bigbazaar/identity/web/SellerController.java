package com.bigbazaar.identity.web;

import com.bigbazaar.identity.seller.SellerDtos.SellerApplicationRequest;
import com.bigbazaar.identity.seller.SellerDtos.SellerDetailResponse;
import com.bigbazaar.identity.seller.SellerDocumentService;
import com.bigbazaar.identity.seller.SellerDtos.DocumentLink;
import com.bigbazaar.identity.seller.SellerDtos.DocumentUploadRequest;
import com.bigbazaar.identity.seller.SellerDtos.DocumentUploadResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerDocumentResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerResponse;
import com.bigbazaar.identity.seller.SellerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final SellerDocumentService documentService;

    public SellerController(SellerService sellerService, SellerDocumentService documentService) {
        this.sellerService = sellerService;
        this.documentService = documentService;
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

    @PostMapping("/me/documents/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Get a one-time link to upload a KYC document (PDF, JPEG or PNG, up to 10 MB)")
    public DocumentUploadResponse requestUpload(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody DocumentUploadRequest request) {
        return documentService.requestUpload(CurrentUser.id(jwt), request);
    }

    @PostMapping("/me/documents/{documentId}/confirm")
    @Operation(summary = "Confirm the file was uploaded; it is checked before admins can see it")
    public SellerDocumentResponse confirmUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
        return documentService.confirmUpload(CurrentUser.id(jwt), documentId);
    }

    @GetMapping("/me/documents/{documentId}/link")
    @Operation(summary = "A 5-minute link to view one of my uploaded documents")
    public DocumentLink documentLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
        return documentService.ownDocumentLink(CurrentUser.id(jwt), documentId);
    }
}
