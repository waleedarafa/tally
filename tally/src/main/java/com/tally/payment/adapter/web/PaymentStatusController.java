package com.tally.payment.adapter.web;

import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A payment's latest attempt, read from Payment (read-your-writes, FR-019). */
@RestController
class PaymentStatusController {

    record StatusResponse(String reference, String state, String failureReason) {
        static StatusResponse of(PaymentStatus status) {
            return new StatusResponse(status.reference(), status.state().name(),
                    status.failureReason().map(Enum::name).orElse(null));
        }
    }

    private final PaymentApi payments;

    PaymentStatusController(PaymentApi payments) {
        this.payments = payments;
    }

    @GetMapping("/v1/payments/{reference}/status")
    ResponseEntity<StatusResponse> status(@PathVariable String reference) {
        return ResponseEntity.of(payments.status(reference).map(StatusResponse::of));
    }
}
