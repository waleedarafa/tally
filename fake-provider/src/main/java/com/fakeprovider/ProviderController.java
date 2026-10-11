package com.fakeprovider;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The provider's API as Tally sees it, plus admin endpoints for tests (brief section 6). */
@RestController
class ProviderController {

    /** How long TIMEOUT_AFTER_CHARGE waits: past Tally's 5 s read timeout. */
    static final long TIMEOUT_AFTER_CHARGE_MILLIS = 6_000;

    record ChargeRequest(String token, long amount, String currency, String reference) {
    }

    record ChargeResponse(String chargeId, String reference, Charge.Outcome outcome) {
    }

    record ModeRequest(List<String> modes) {
    }

    private final Ledger ledger;
    private final IdempotencyKeys keys;
    private final Clock clock;
    private final AtomicReference<Modes> modes = new AtomicReference<>(Modes.NORMAL);
    private final AtomicLong windowSecond = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong unavailable = new AtomicLong();
    private final AtomicLong charged = new AtomicLong();
    private final AtomicLong windowCount = new AtomicLong();
    private final AtomicLong replayed = new AtomicLong();

    ProviderController(Ledger ledger, IdempotencyKeys keys, Clock clock) {
        this.ledger = ledger;
        this.keys = keys;
        this.clock = clock;
    }

    /** Simulates the hosted card form: the card stays with the provider, Tally gets a token (FR-015). */
    @PostMapping("/tokens")
    Map<String, String> token() {
        return Map.of("token", ledger.issueToken());
    }

    /**
     * Charges a token. With an {@code Idempotency-Key} (A22), the first response is saved once the charge
     * has executed and replayed for every repeat; the outage and the rate limit stand in front of that
     * layer, so their refusals are not saved. Without a key, every request that passes them charges (A8).
     */
    @PostMapping("/charges")
    ResponseEntity<?> charge(@RequestBody ChargeRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) throws InterruptedException {
        ledger.requested(request.reference());
        totalRequests.incrementAndGet();
        Modes m = modes.get();
        if (m.outage()) {
            unavailable.incrementAndGet();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (m.rateLimitPerSecond() > 0 && overLimit(m.rateLimitPerSecond())) {
            rateLimited.incrementAndGet();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1").build();
        }
        if (key != null) {
            switch (keys.begin(key, request)) {
                case IdempotencyKeys.Replay replay -> {
                    replayed.incrementAndGet();
                    return ResponseEntity.ok(replay.response());
                }
                case IdempotencyKeys.Conflict conflict -> {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "idempotency_conflict"));
                }
                case IdempotencyKeys.Mismatch mismatch -> {
                    return ResponseEntity.badRequest().body(Map.of("error", "idempotency_error"));
                }
                case IdempotencyKeys.Execute execute -> { }
            }
        }
        Charge charge = ledger.charge(request.token(), request.amount(), request.currency(), request.reference(), m);
        charged.incrementAndGet();
        Charge.Outcome reported = m.lie() ? opposite(charge.outcome()) : charge.outcome();
        ChargeResponse response = new ChargeResponse(charge.chargeId(), charge.reference(), reported);
        if (m.slowMillis() > 0) {
            // Still executing: a repeat with the same key meanwhile is a conflict.
            Thread.sleep(m.slowMillis());
        }
        if (key != null) {
            keys.save(key, request, response);
        }
        if (m.timeoutAfterCharge()) {
            // Executed and saved; only the answer is late, as when the network loses it.
            Thread.sleep(TIMEOUT_AFTER_CHARGE_MILLIS);
        }
        return ResponseEntity.ok(response);
    }

    /** The recorded truth for a reference. */
    @GetMapping("/charges")
    List<Charge> charges(@RequestParam String reference) {
        if (modes.get().lookupLag()) {
            return List.of();
        }
        return ledger.chargesFor(reference);
    }

    @PostMapping("/admin/mode")
    Modes mode(@RequestBody ModeRequest request) {
        Modes parsed = Modes.parse(request.modes() == null ? List.of() : request.modes());
        modes.set(parsed);
        return parsed;
    }

    /** Test-only: as if the retention period (24 h) had passed for every idempotency key. */
    @PostMapping("/admin/idempotency-keys/prune")
    void pruneIdempotencyKeys() {
        keys.pruneAll();
    }

    @GetMapping("/admin/mode")
    Modes mode() {
        return modes.get();
    }

    /** Lets tests prove nobody was charged twice. */
    @GetMapping("/admin/charges/count")
    Map<String, Integer> count(@RequestParam String reference) {
        return Map.of("count", ledger.chargesFor(reference).size());
    }

    /** Every charge request for a reference, including those answered 429 or 503. */
    @GetMapping("/admin/requests/count")
    Map<String, Integer> requests(@RequestParam String reference) {
        return Map.of("count", ledger.requestsFor(reference));
    }

    /** Totals since the provider started: charge requests, refusals (429, 503) and charges recorded. */
    @GetMapping("/admin/stats")
    Map<String, Long> stats() {
        return Map.of("requests", totalRequests.get(), "rateLimited", rateLimited.get(),
                "unavailable", unavailable.get(), "charged", charged.get(), "replayed", replayed.get());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badMode(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    /** A fixed one-second window: at most {@code limit} requests per wall-clock second. */
    private synchronized boolean overLimit(int limit) {
        long second = clock.millis() / 1000;
        if (windowSecond.getAndSet(second) != second) {
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() > limit;
    }

    private static Charge.Outcome opposite(Charge.Outcome recorded) {
        return recorded == Charge.Outcome.SUCCEEDED ? Charge.Outcome.DECLINED_HARD : Charge.Outcome.SUCCEEDED;
    }
}
