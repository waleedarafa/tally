package com.tally.payment.application;

import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.ChargeResult;
import com.tally.payment.domain.PaymentGateway.NotChargedException;
import com.tally.platform.ClaimGate;
import com.tally.shared.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Isolates Tally from the provider (brief section 5, FR-005): a per-instance rate limit (the
 * provider's limit divided by the instance count) and a circuit breaker. The breaker is also the
 * payment queue's claim gate: while it is open, workers stop claiming. The worker pool is the
 * bulkhead. Resilience4j core modules, with the injected clock (D10); no threads of their own.
 */
@Component
class ProviderIsolation implements ClaimGate {

    private static final Duration HELD_BACK_RETRY = Duration.ofSeconds(1);

    private final PaymentGateway gateway;
    private final CircuitBreaker breaker;
    private final RateLimiter limiter;
    private final Clock clock;

    ProviderIsolation(PaymentGateway gateway, PaymentProperties properties, Clock clock, MeterRegistry meters) {
        this.gateway = gateway;
        this.clock = clock;
        PaymentProperties.Breaker b = properties.breaker();
        this.breaker = CircuitBreaker.of("payment-provider", CircuitBreakerConfig.custom()
                .clock(clock)
                .slidingWindowSize(b.slidingWindow())
                .minimumNumberOfCalls(b.minimumCalls())
                .failureRateThreshold(b.failureRatePercent())
                .waitDurationInOpenState(b.openFor())
                .permittedNumberOfCallsInHalfOpenState(b.trialCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                // A 429 is the provider asking us to slow down, not failing: the rate limiter's business.
                .ignoreException(e -> e instanceof NotChargedException n && n.why() == NotChargedException.Why.RATE_LIMITED)
                .build());
        // One permit every (1 s / share), not the whole share once a second: the provider counts its own
        // seconds, and a burst at the end of one of our windows plus one at the start of the next landed in
        // a single provider second (AT-10: 2.5% of calls refused with 429 before this change).
        this.limiter = RateLimiter.of("payment-provider", RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(permitInterval(properties.provider().rateLimitPerSecond(),
                        properties.instances(), properties.provider().rateLimitTargetPercent()))
                .timeoutDuration(properties.provider().rateLimitWait())
                .build());
        Gauge.builder("tally.payment.breaker.state", breaker, ProviderIsolation::stateValue)
                .description("Provider circuit breaker: 0 closed, 1 open, 2 half-open")
                .strongReference(true).register(meters);
    }

    /** Claim gate: closed while the breaker is open and its wait has not elapsed. */
    @Override
    public boolean open() {
        if (breaker.getState() != CircuitBreaker.State.OPEN) {
            return true;
        }
        // After the wait, this moves the breaker to half-open; the permission is handed back for the call.
        if (breaker.tryAcquirePermission()) {
            breaker.releasePermission();
            return true;
        }
        return false;
    }

    /**
     * Asks for one provider call: a rate-limit permit, then the breaker's permission. False means Tally
     * holds the call back; the provider is not called, so the attempt stays REQUESTED and the item is
     * retried after {@link #HELD_BACK_RETRY}. True must be followed by {@link #charge} or {@link #release}.
     */
    boolean permit() {
        return limiter.acquirePermission() && breaker.tryAcquirePermission();
    }

    /** Hands back a permission that will not be used (the claim was lost). */
    void release() {
        breaker.releasePermission();
    }

    /**
     * Calls the provider under a permission from {@link #permit}, and records the outcome in the breaker.
     * A 429 is ignored by the breaker (the rate limiter's business).
     */
    ChargeResult charge(String paymentToken, Money amount, String reference, String idempotencyKey) {
        Instant start = clock.instant();
        try {
            ChargeResult result = gateway.charge(paymentToken, amount, reference, idempotencyKey);
            breaker.onSuccess(Duration.between(start, clock.instant()).toNanos(), TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException e) {
            breaker.onError(Duration.between(start, clock.instant()).toNanos(), TimeUnit.NANOSECONDS, e);
            throw e;
        }
    }

    static Duration heldBackRetry() {
        return HELD_BACK_RETRY;
    }

    CircuitBreaker.State state() {
        return breaker.getState();
    }

    private static double stateValue(CircuitBreaker breaker) {
        return switch (breaker.getState()) {
            case OPEN, FORCED_OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> 0;
        };
    }

    /**
     * Time between two calls on one instance: each instance takes an equal share of
     * {@code targetPercent} of the provider's limit. At 100 calls a second, two instances and 95%,
     * one call every 21.05 ms (47.5 a second each, 95 together).
     */
    static Duration permitInterval(int limitPerSecond, int instances, int targetPercent) {
        long callsPerHundredSeconds = Math.max(1, (long) limitPerSecond * targetPercent / Math.max(1, instances));
        return Duration.ofNanos(100_000_000_000L / callsPerHundredSeconds);
    }
}
