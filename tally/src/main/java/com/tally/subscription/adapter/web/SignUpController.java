package com.tally.subscription.adapter.web;

import com.tally.account.AccountApi;
import com.tally.account.UnknownAccountException;
import com.tally.offers.UnknownPlanException;
import com.tally.subscription.AccountBlockedException;
import com.tally.subscription.AlreadySubscribedException;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import com.tally.subscription.domain.IdempotentRequests;
import com.tally.subscription.domain.IdempotentRequests.Act;
import com.tally.subscription.domain.IdempotentRequests.Claim;
import com.tally.subscription.domain.IdempotentRequests.InProgress;
import com.tally.subscription.domain.IdempotentRequests.Replay;
import com.tally.subscription.domain.IdempotentRequests.Reused;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/subscriptions}: sign-up (design.md §8; docs/api/openapi.yaml; ADR-014, ADR-019). Records the
 * payment token, starts the subscription, and answers at once with {@code accessWithinSeconds: 5}: the first
 * invoice and its payment follow asynchronously. Replaces checkout's wait-then-poll (FR-019).
 *
 * <p>The {@code Idempotency-Key} is required, scoped by account until callers are authenticated. It is claimed
 * before acting (ADR-019); the response is stored in the same transaction as the sign-up, so both commit or
 * neither does; both are kept 24 hours. A repeat of the same request returns the stored response, errors
 * included, byte for byte. A key still held after the request timeout belonged to a request that died: the next
 * request takes it over and acts again, and the one-subscription-per-account constraint still stops a duplicate.
 * Every error is an RFC 9457 problem with the type codes of design.md §8, or {@code about:blank}.
 */
@RestController
@EnableConfigurationProperties(SignUpProperties.class)
class SignUpController {

    /** ADR-019: how long a key and its response are kept. */
    static final Duration KEPT_FOR = Duration.ofHours(24);

    /** ADR-014: the promise the app shows while access is activated. */
    static final int ACCESS_WITHIN_SECONDS = 5;

    record SignUpRequest(UUID accountId, String planId, String paymentToken) {
    }

    record SignUpResponse(UUID subscriptionId, String status, int accessWithinSeconds) {
    }

    private final AccountApi accounts;
    private final SubscriptionApi subscriptions;
    private final IdempotentRequests requests;
    private final TransactionTemplate transactions;
    private final SignUpProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    SignUpController(AccountApi accounts, SubscriptionApi subscriptions, IdempotentRequests requests,
            TransactionTemplate transactions, SignUpProperties properties, JsonMapper json, Clock clock) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.requests = requests;
        this.transactions = transactions;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping("/v1/subscriptions")
    ResponseEntity<String> signUp(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody SignUpRequest request) {
        if (key == null || key.isBlank() || key.length() > 255) {
            return problem(HttpStatus.BAD_REQUEST, "about:blank", "An Idempotency-Key header is required",
                    "Send a key you made for this sign-up, such as a UUID, of at most 255 characters.");
        }
        if (request.accountId() == null || isBlank(request.planId()) || isBlank(request.paymentToken())) {
            // Not stored: without an account there is no scope to store the key in.
            return problem(HttpStatus.BAD_REQUEST, "about:blank", "Not a sign-up",
                    "accountId, planId and paymentToken are required.");
        }
        Claim claim = requests.claim(request.accountId(), key, fingerprint(request), clock.instant(), KEPT_FOR,
                properties.requestTimeout());
        return switch (claim) {
            case Replay stored -> ResponseEntity.status(stored.status())
                    .contentType(MediaType.parseMediaType(stored.contentType())).body(stored.body());
            case Reused reused -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "/problems/idempotency-key-reused",
                    "This Idempotency-Key was used for a different request", "Use a new key for a new request.");
            case InProgress inProgress -> problem(HttpStatus.CONFLICT, "about:blank",
                    "A request with this Idempotency-Key is still being handled", "Try again shortly.");
            case Act act -> act(key, request);
        };
    }

    /**
     * The sign-up and its stored response in one transaction. A refusal rolls the sign-up back, then is
     * stored on its own: it changed nothing. An unexpected failure releases the key, so the client may try
     * again at once; a request that dies without releasing it is taken over after the request timeout.
     */
    private ResponseEntity<String> act(String key, SignUpRequest request) {
        try {
            return transactions.execute(tx -> store(key, request, signUp(request)));
        } catch (UnknownAccountException | UnknownPlanException | AlreadySubscribedException
                | AccountBlockedException refused) {
            ResponseEntity<String> problem = refusal(refused);
            return transactions.execute(tx -> store(key, request, problem));
        } catch (RuntimeException unexpected) {
            requests.release(request.accountId(), key);
            throw unexpected;
        }
    }

    private ResponseEntity<String> store(String key, SignUpRequest request, ResponseEntity<String> response) {
        requests.complete(request.accountId(), key, response.getStatusCode().value(),
                response.getHeaders().getContentType().toString(), response.getBody());
        return response;
    }

    private ResponseEntity<String> signUp(SignUpRequest request) {
        accounts.recordPaymentToken(request.accountId(), request.paymentToken());
        SubscriptionView subscription = subscriptions.subscribe(request.accountId(), request.planId());
        // Slice 1R models ACTIVE only; trial and later states come with the subscription lifecycle.
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(new SignUpResponse(subscription.id(), "ACTIVE", ACCESS_WITHIN_SECONDS)));
    }

    private ResponseEntity<String> refusal(RuntimeException refused) {
        return switch (refused) {
            case UnknownAccountException e -> problem(HttpStatus.NOT_FOUND, "about:blank", "No such account",
                    e.getMessage());
            case UnknownPlanException e -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "/problems/offer-not-available",
                    "This plan is not offered to this account", e.getMessage());
            case AlreadySubscribedException e -> problem(HttpStatus.CONFLICT, "/problems/already-subscribed",
                    "The account already has a subscription", e.getMessage());
            case AccountBlockedException e -> problem(HttpStatus.CONFLICT, "/problems/account-blocked",
                    "The account is blocked", e.getMessage());
            default -> throw refused;
        };
    }

    /** RFC 9457. Written out, not as Spring's ProblemDetail, so a stored refusal is replayed byte for byte. */
    private ResponseEntity<String> problem(HttpStatus status, String type, String title, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(json.writeValueAsString(body));
    }

    /** What makes two requests "the same request" for one key. */
    private String fingerprint(SignUpRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
