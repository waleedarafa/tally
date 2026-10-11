package com.tally.subscription.adapter.web;

import com.tally.account.AccountApi;
import com.tally.account.UnknownAccountException;
import com.tally.offers.UnknownPlanException;
import com.tally.platform.IdempotencyKeys;
import com.tally.platform.IdempotencyStore;
import com.tally.platform.IdempotentEndpoints;
import com.tally.subscription.AccountBlockedException;
import com.tally.subscription.AlreadySubscribedException;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/subscriptions}: sign-up (design.md §8; docs/api/openapi.yaml; ADR-014, ADR-019). Records the
 * payment token, starts the subscription, and answers at once with {@code accessWithinSeconds: 5}: the first
 * invoice and its payment follow asynchronously.
 *
 * <p>The {@code Idempotency-Key} is required, scoped by account until callers are authenticated, and handled by
 * the platform's shared mechanism ({@link IdempotentEndpoints}): the sign-up and its stored response commit
 * together; a request that died is taken over after the request timeout, and the one-subscription-per-account
 * constraint still stops a duplicate. Every error is an RFC 9457 problem with the type codes of design.md §8,
 * or {@code about:blank}.
 */
@RestController
class SignUpController {

    /** ADR-014: the promise the app shows while access is activated. */
    static final int ACCESS_WITHIN_SECONDS = 5;

    record SignUpRequest(UUID accountId, String planId, String paymentToken) {
    }

    record SignUpResponse(UUID subscriptionId, String status, int accessWithinSeconds) {
    }

    private final AccountApi accounts;
    private final SubscriptionApi subscriptions;
    private final IdempotentEndpoints endpoints;
    private final IdempotencyStore keys;
    private final JsonMapper json;

    SignUpController(AccountApi accounts, SubscriptionApi subscriptions, IdempotentEndpoints endpoints,
            IdempotencyKeys idempotencyKeys, JsonMapper json) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.endpoints = endpoints;
        this.keys = idempotencyKeys.register("subscription.idempotency_key");
        this.json = json;
    }

    @PostMapping("/v1/subscriptions")
    ResponseEntity<String> signUp(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody SignUpRequest request) {
        if (request.accountId() == null || isBlank(request.planId()) || isBlank(request.paymentToken())) {
            // Not stored: without an account there is no scope to store the key in.
            return endpoints.problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a sign-up",
                    "accountId, planId and paymentToken are required.");
        }
        return endpoints.handle(keys, request.accountId().toString(), key, request, () -> signUp(request),
                this::refusal);
    }

    private ResponseEntity<String> signUp(SignUpRequest request) {
        accounts.recordPaymentToken(request.accountId(), request.paymentToken());
        SubscriptionView subscription = subscriptions.subscribe(request.accountId(), request.planId());
        // Slice 1R models ACTIVE only; trial and later states come with the subscription lifecycle.
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(new SignUpResponse(subscription.id(), "ACTIVE", ACCESS_WITHIN_SECONDS)));
    }

    private Optional<ResponseEntity<String>> refusal(RuntimeException refused) {
        return Optional.ofNullable(switch (refused) {
            case UnknownAccountException e -> endpoints.problem(HttpStatus.NOT_FOUND, "about:blank", "No such account",
                    e.getMessage());
            case UnknownPlanException e -> endpoints.problem(HttpStatus.UNPROCESSABLE_CONTENT,
                    "/problems/offer-not-available", "This plan is not offered to this account", e.getMessage());
            case AlreadySubscribedException e -> endpoints.problem(HttpStatus.CONFLICT, "/problems/already-subscribed",
                    "The account already has a subscription", e.getMessage());
            case AccountBlockedException e -> endpoints.problem(HttpStatus.CONFLICT, "/problems/account-blocked",
                    "The account is blocked", e.getMessage());
            default -> null;
        });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
