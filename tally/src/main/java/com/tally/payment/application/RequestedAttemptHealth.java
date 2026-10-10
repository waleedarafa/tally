package com.tally.payment.application;

import com.tally.payment.domain.PaymentAttempts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Health of attempts waiting to be sent (Slice 1R PR #20 review; D9). A held-back call leaves its attempt
 * REQUESTED: it was never sent, so its outcome is not unknown (proposed ADR-026). An attempt that waits
 * long means the collection queue is stuck. Read from the database when scraped, so the signal is shared
 * across instances and keeps ageing if every worker stops.
 * <ul>
 *   <li>{@code tally.payment.requested.oldest_age_seconds}: age of the oldest REQUESTED attempt; 0 if none</li>
 *   <li>{@code tally.payment.requested.stuck}: 1 when that age exceeds
 *       {@code tally.payment.requested-alert-after} (default 15 minutes); otherwise 0</li>
 * </ul>
 */
@Component
class RequestedAttemptHealth {

    private final PaymentAttempts attempts;
    private final Clock clock;

    RequestedAttemptHealth(PaymentAttempts attempts, PaymentProperties properties, MeterRegistry meters, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
        Duration alertAfter = properties.requestedAlertAfter();
        Gauge.builder("tally.payment.requested.oldest_age_seconds", this, h -> h.oldestAge().toMillis() / 1000.0)
                .baseUnit("seconds").strongReference(true).register(meters);
        Gauge.builder("tally.payment.requested.stuck", this, h -> h.oldestAge().compareTo(alertAfter) > 0 ? 1 : 0)
                .strongReference(true).register(meters);
    }

    Duration oldestAge() {
        return attempts.oldestWaitingToBeSent()
                .map(made -> Duration.between(made, clock.instant()))
                .filter(age -> !age.isNegative())
                .orElse(Duration.ZERO);
    }
}
