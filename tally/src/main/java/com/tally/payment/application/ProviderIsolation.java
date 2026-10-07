package com.tally.payment.application;

import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.ChargeResult;
import com.tally.payment.domain.PaymentGateway.NotChargedException;
import com.tally.platform.ClaimGate;
import com.tally.shared.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Isolates Tally from the provider (brief section 5, ADR-005): a per-instance rate limit (the
 * provider's limit divided by the instance count) and a circuit breaker. The breaker is also the
 * payment queue's claim gate: while it is open, workers stop claiming. The worker pool is the
 * bulkhead. Resilience4j core modules, with the injected clock (D4); no threads of their own.
 */
@Component
class ProviderIsolation implements ClaimGate {

    private static final Duration HELD_BACK_RETRY = Duration.ofSeconds(1);

    private final PaymentGateway gateway;
    private final CircuitBreaker breaker;
    private final RateLimiter limiter;

    ProviderIsolation(PaymentGateway gateway, PaymentProperties properties, Clock clock, MeterRegistry meters) {
        this.gateway = gateway;
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
        int perInstance = Math.max(1, properties.provider().rateLimitPerSecond() / Math.max(1, properties.instances()));
        this.limiter = RateLimiter.of("payment-provider", RateLimiterConfig.custom()
                .limitForPeriod(perInstance)
                .limitRefreshPeriod(Duration.ofSeconds(1))
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

    ChargeResult charge(String paymentToken, Money amount, String reference) {
        if (!limiter.acquirePermission()) {
            throw new NotChargedException(NotChargedException.Why.HELD_BACK, HELD_BACK_RETRY,
                    "Local rate limit reached for " + reference, null);
        }
        try {
            return breaker.executeSupplier(() -> gateway.charge(paymentToken, amount, reference));
        } catch (CallNotPermittedException e) {
            throw new NotChargedException(NotChargedException.Why.HELD_BACK, HELD_BACK_RETRY,
                    "Provider circuit breaker open for " + reference, e);
        }
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
}
