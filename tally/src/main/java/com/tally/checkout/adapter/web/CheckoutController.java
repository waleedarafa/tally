package com.tally.checkout.adapter.web;

import com.tally.account.UnknownAccountException;
import com.tally.offers.UnknownPlanException;
import com.tally.checkout.application.Checkout;
import com.tally.subscription.AlreadySubscribedException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/subscriptions: checkout. Answers 201 with SUCCEEDED or FAILED when the first payment
 * finishes within the wait, otherwise 202 with PROCESSING; the client then polls
 * GET /v1/payments/{reference}/status (FR-019).
 */
@RestController
class CheckoutController {

    record CheckoutRequest(UUID accountId, String planId, String paymentToken) {
    }

    record CheckoutResponse(UUID subscriptionId, UUID invoiceId, String paymentReference, String status) {
    }

    private final Checkout checkout;

    CheckoutController(Checkout checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/v1/subscriptions")
    ResponseEntity<CheckoutResponse> subscribe(@RequestBody CheckoutRequest request) {
        Checkout.Outcome outcome = checkout.checkout(request.accountId(), request.planId(), request.paymentToken());
        HttpStatus status = outcome.result() == Checkout.Result.PROCESSING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new CheckoutResponse(outcome.subscriptionId(),
                outcome.invoiceId().orElse(null), outcome.paymentReference().orElse(null), outcome.result().name()));
    }

    @ExceptionHandler(UnknownAccountException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void unknownAccount() {
    }

    @ExceptionHandler({UnknownPlanException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void badRequest() {
    }

    @ExceptionHandler(AlreadySubscribedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void alreadySubscribed() {
    }
}
