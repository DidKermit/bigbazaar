package com.bigbazaar.identity.seller;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for verification attempts that are due. Safe on several replicas (rows are locked SKIP LOCKED). */
@Component
class SellerVerificationWorker {

    private final SellerVerificationService service;

    SellerVerificationWorker(SellerVerificationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${bigbazaar.kyc.poll-interval:2s}")
    void poll() {
        while (service.processDue(10) == 10) {
            // keep draining while full batches come back
        }
    }
}
