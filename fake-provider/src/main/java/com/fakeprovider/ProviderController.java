package com.fakeprovider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final AtomicReference<Modes> modes = new AtomicReference<>(Modes.NORMAL);
    private final AtomicLong windowSecond = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong unavailable = new AtomicLong();
    private final AtomicLong charged = new AtomicLong();
    private final AtomicLong windowCount = new AtomicLong();

    ProviderController(Ledger ledger) {
        this.ledger = ledger;
    }

    /** Simulates the hosted card form: the card stays with the provider, Tally gets a token (FR-015). */
    @PostMapping("/tokens")
    Map<String, String> token() {
        return Map.of("token", ledger.issueToken());
    }

    @PostMapping("/charges")
    ResponseEntity<ChargeResponse> charge(@RequestBody ChargeRequest request) throws InterruptedException {
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
        Charge charge = ledger.charge(request.token(), request.amount(), request.currency(), request.reference(), m);
        charged.incrementAndGet();
        if (m.timeoutAfterCharge()) {
            Thread.sleep(TIMEOUT_AFTER_CHARGE_MILLIS);
        }
        if (m.slowMillis() > 0) {
            Thread.sleep(m.slowMillis());
        }
        Charge.Outcome reported = m.lie() ? opposite(charge.outcome()) : charge.outcome();
        return ResponseEntity.ok(new ChargeResponse(charge.chargeId(), charge.reference(), reported));
    }

    /** The recorded truth for a reference. */
    @GetMapping("/charges")
    List<Charge> charges(@RequestParam String reference) {
        return ledger.chargesFor(reference);
    }

    @PostMapping("/admin/mode")
    Modes mode(@RequestBody ModeRequest request) {
        Modes parsed = Modes.parse(request.modes() == null ? List.of() : request.modes());
        modes.set(parsed);
        return parsed;
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
                "unavailable", unavailable.get(), "charged", charged.get());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badMode(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    /** A fixed one-second window: at most {@code limit} requests per wall-clock second. */
    private synchronized boolean overLimit(int limit) {
        long second = System.currentTimeMillis() / 1000;
        if (windowSecond.getAndSet(second) != second) {
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() > limit;
    }

    private static Charge.Outcome opposite(Charge.Outcome recorded) {
        return recorded == Charge.Outcome.SUCCEEDED ? Charge.Outcome.DECLINED_HARD : Charge.Outcome.SUCCEEDED;
    }
}
