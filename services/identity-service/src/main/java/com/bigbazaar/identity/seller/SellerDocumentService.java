package com.bigbazaar.identity.seller;

import com.bigbazaar.common.events.Topics;
import com.bigbazaar.identity.common.BusinessException;
import com.bigbazaar.identity.common.ErrorCode;
import com.bigbazaar.identity.common.IdentityEvents;
import com.bigbazaar.identity.outbox.OutboxWriter;
import com.bigbazaar.identity.seller.SellerDtos.DocumentLink;
import com.bigbazaar.identity.seller.SellerDtos.DocumentUploadRequest;
import com.bigbazaar.identity.seller.SellerDtos.DocumentUploadResponse;
import com.bigbazaar.identity.seller.SellerDtos.SellerDocumentResponse;
import com.bigbazaar.identity.seller.SellerDtos.UploadInstructions;
import com.bigbazaar.identity.storage.ObjectStorage;
import com.bigbazaar.identity.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KYC document upload in two steps: ask for an upload link, send the file straight to storage, then
 * confirm. Confirmation checks the file exists, has the approved size, and really is the type it
 * claims (magic bytes) before an admin can see it.
 */
@Service
public class SellerDocumentService {

    private static final Logger log = LoggerFactory.getLogger(SellerDocumentService.class);
    private static final Duration UPLOAD_LINK_TTL = Duration.ofMinutes(10);
    private static final Duration DOWNLOAD_LINK_TTL = Duration.ofMinutes(5);
    private static final Duration ABANDONED_AFTER = Duration.ofHours(24);
    private static final Set<SellerStatus> UPLOAD_ALLOWED =
            Set.of(SellerStatus.PENDING_VERIFICATION, SellerStatus.UNDER_REVIEW, SellerStatus.APPROVED);

    private final SellerService sellers;
    private final SellerDocumentRepository documents;
    private final UserService userService;
    private final ObjectStorage storage;
    private final OutboxWriter outbox;
    private final Clock clock;

    public SellerDocumentService(SellerService sellers, SellerDocumentRepository documents, UserService userService,
                                 ObjectStorage storage, OutboxWriter outbox, Clock clock) {
        this.sellers = sellers;
        this.documents = documents;
        this.userService = userService;
        this.storage = storage;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public DocumentUploadResponse requestUpload(UUID userId, DocumentUploadRequest request) {
        userService.requireActive(userId);
        Seller seller = sellers.findByUser(userId);
        if (!UPLOAD_ALLOWED.contains(seller.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Documents cannot be added while the seller is " + seller.getStatus() + ".");
        }
        if (!DocumentFormats.isAllowed(request.contentType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only PDF, JPEG and PNG files are accepted.");
        }
        UUID documentId = UUID.randomUUID();
        // The key is ours, never the user's file name, so nobody can choose where a file lands.
        String key = "sellers/" + seller.getId() + "/documents/" + documentId
                + DocumentFormats.extension(request.contentType());
        SellerDocument document = documents.save(new SellerDocument(documentId, seller.getId(),
                request.documentType(), key, request.contentType(), request.sizeBytes(),
                cleanFileName(request.fileName()), clock.instant()));
        ObjectStorage.UploadTarget target =
                storage.presignUpload(key, request.contentType(), request.sizeBytes(), UPLOAD_LINK_TTL);
        return new DocumentUploadResponse(SellerDocumentResponse.from(document),
                new UploadInstructions(target.url().toString(), target.method(), target.headers(), target.expiresAt()));
    }

    // noRollbackFor: an invalid file must still be recorded as INVALID.
    @Transactional(noRollbackFor = BusinessException.class)
    public SellerDocumentResponse confirmUpload(UUID userId, UUID documentId) {
        userService.requireActive(userId);
        Seller seller = sellers.findByUser(userId);
        SellerDocument document = documents.findByIdAndSellerId(documentId, seller.getId())
                .orElseThrow(SellerDocumentService::notFound);
        if (document.getStatus() == SellerDocument.Status.UPLOADED) {
            return SellerDocumentResponse.from(document); // confirming twice is harmless
        }
        if (document.getStatus() != SellerDocument.Status.PENDING_UPLOAD) {
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT,
                    "This upload is " + document.getStatus() + ". Request a new upload link.");
        }
        var stored = storage.head(document.getStorageKey()).orElseThrow(() -> new BusinessException(
                ErrorCode.UPLOAD_NOT_FOUND, "The file hasn't arrived yet. Upload it, then confirm."));
        boolean valid = stored.sizeBytes() == document.getSizeBytes()
                && DocumentFormats.matches(document.getContentType(),
                storage.readPrefix(document.getStorageKey(), DocumentFormats.SNIFF_BYTES));
        if (!valid) {
            storage.delete(document.getStorageKey());
            document.markInvalid();
            log.warn("Rejected seller document documentId={} sellerId={} declaredType={} size={}/{}",
                    documentId, seller.getId(), document.getContentType(), stored.sizeBytes(),
                    document.getSizeBytes());
            throw new BusinessException(ErrorCode.INVALID_DOCUMENT,
                    "The file isn't a valid " + document.getContentType() + " of the declared size.");
        }
        document.markUploaded(clock.instant());
        outbox.write(Topics.IDENTITY_EVENTS, "Seller", seller.getId().toString(),
                IdentityEvents.SELLER_DOCUMENT_UPLOADED,
                new IdentityEvents.SellerDocumentUploaded(seller.getId(), documentId, document.getDocumentType(),
                        document.getContentType(), document.getSizeBytes(), userId));
        return SellerDocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public DocumentLink ownDocumentLink(UUID userId, UUID documentId) {
        userService.requireActive(userId);
        return link(sellers.findByUser(userId).getId(), documentId);
    }

    @Transactional(readOnly = true)
    public DocumentLink adminDocumentLink(UUID sellerId, UUID documentId) {
        return link(sellers.findById(sellerId).getId(), documentId);
    }

    /** Clears links that were issued but never used, and their files if half-written. Safe on every replica. */
    @Scheduled(fixedDelayString = "${bigbazaar.storage.cleanup-interval:1h}")
    @Transactional
    public void expireAbandonedUploads() {
        Instant cutoff = clock.instant().minus(ABANDONED_AFTER);
        for (SellerDocument document : documents.findByStatusAndCreatedAtBefore(
                SellerDocument.Status.PENDING_UPLOAD, cutoff, Limit.of(200))) {
            storage.delete(document.getStorageKey());
            document.markExpired();
        }
    }

    private DocumentLink link(UUID sellerId, UUID documentId) {
        SellerDocument document = documents.findByIdAndSellerId(documentId, sellerId)
                .filter(d -> d.getStatus() == SellerDocument.Status.UPLOADED)
                .orElseThrow(SellerDocumentService::notFound);
        return new DocumentLink(storage.presignDownload(document.getStorageKey(), DOWNLOAD_LINK_TTL).toString(),
                clock.instant().plus(DOWNLOAD_LINK_TTL));
    }

    private static String cleanFileName(String name) {
        if (name == null) {
            return null;
        }
        String base = name.replaceAll(".*[/\\\\]", "").replaceAll("[\\p{Cntrl}]", "").trim();
        return base.isEmpty() ? null : base.substring(0, Math.min(base.length(), 200));
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "Document not found.");
    }
}
