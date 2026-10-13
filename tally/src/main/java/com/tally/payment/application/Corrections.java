package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.payment.domain.PaymentAttempt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Records what the provider's record, or a repeat with an attempt's key, showed (FR-018; ADR-026),
 * settles the collection request, and publishes the matching event with the request's version
 * as its sequence (ADR-016). Called inside the transaction that changes the attempt. Every correction
 * increments {@code tally.payment.outcome_corrected{from,to}}.
 */
@Component
class Corrections {

    private static final Logger log = LoggerFactory.getLogger(Corrections.class);

    private final CollectionRequestSettlement requests;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final Clock clock;

    Corrections(CollectionRequestSettlement requests, ApplicationEventPublisher events, MeterRegistry meters,
            Clock clock) {
        this.requests = requests;
        this.events = events;
        this.meters = meters;
        this.clock = clock;
        // Registered at 0 from startup: Prometheus's increase() cannot see a counter's first step when its series
        // first appears at 1, so ProviderFalseDecline would miss the first false decline (ADR-030).
        CORRECTIONS.forEach(pair -> counter(pair[0], pair[1]));
    }

    /** Every correction this class records, as {from, to}. */
    static final List<AttemptState[]> CORRECTIONS = List.of(
            new AttemptState[] {AttemptState.SENT, AttemptState.UNKNOWN},
            new AttemptState[] {AttemptState.UNKNOWN, AttemptState.SUCCEEDED},
            new AttemptState[] {AttemptState.DECLINED, AttemptState.SUCCEEDED},
            new AttemptState[] {AttemptState.UNKNOWN, AttemptState.DECLINED});

    private Counter counter(AttemptState from, AttemptState to) {
        return meters.counter("tally.payment.outcome_corrected", "from", from.name(), "to", to.name());
    }

    /** The attempt charged: SUCCEEDED, from UNKNOWN, or from a DECLINED the provider reported wrongly. */
    void charged(PaymentAttempt attempt, String chargeId) {
        AttemptState was = attempt.resolvedCharged(chargeId, clock.instant());
        corrected(was, AttemptState.SUCCEEDED, attempt);
        Long sequence = requests.settle(attempt);
        events.publishEvent(new PaymentSucceeded(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                attempt.reference(), attempt.amount(), chargeId, sequence));
    }

    /** An UNKNOWN attempt was declined. */
    void declined(PaymentAttempt attempt, FailureReason reason) {
        attempt.resolvedDeclined(reason, clock.instant());
        corrected(AttemptState.UNKNOWN, AttemptState.DECLINED, attempt);
        Long sequence = requests.settle(attempt);
        events.publishEvent(new PaymentFailed(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                attempt.reference(), attempt.amount(), reason, sequence));
    }

    void corrected(AttemptState from, AttemptState to, PaymentAttempt attempt) {
        log.warn("Corrected {} from {} to {}", attempt.reference(), from, to);
        counter(from, to).increment();
    }
}
