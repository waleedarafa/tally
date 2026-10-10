package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttemptRepository;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.RecordedCharge;
import com.tally.platform.PeriodicJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation (FR-018): asks the provider for the recorded truth of UNKNOWN attempts, and of
 * FAILED attempts from the last 48 hours, on a backoff of 5 minutes, 1 hour, then daily. It reads
 * with no transaction open (FR-005), then records any correction and publishes the matching event.
 * Every correction increments {@code tally.payment.outcome_corrected{from,to}}. A periodic job: runs
 * on one instance at a time and reports its health (FR-023).
 */
@Component
class Reconciliation implements PeriodicJob {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final PaymentAttemptRepository attempts;
    private final CollectionRequests requests;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final PaymentProperties properties;
    private final Clock clock;

    Reconciliation(PaymentAttemptRepository attempts, CollectionRequests requests, PaymentGateway gateway,
            ApplicationEventPublisher events, TransactionTemplate transactions, MeterRegistry meters,
            PaymentProperties properties, Clock clock) {
        this.attempts = attempts;
        this.requests = requests;
        this.gateway = gateway;
        this.events = events;
        this.transactions = transactions;
        this.meters = meters;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "payment-reconciliation";
    }

    @Override
    public String table() {
        return "payment.jobs";
    }

    @Override
    public Duration interval() {
        return properties.reconciliation().interval();
    }

    /** UNKNOWN attempts waiting for the truth. */
    @Override
    public long waitingWork() {
        return attempts.countByState(AttemptState.UNKNOWN);
    }

    @Override
    public void run() {
        for (PaymentAttempt due : attempts.findTop100ByReconcileAtLessThanEqualOrderByReconcileAt(clock.instant())) {
            List<RecordedCharge> truth;
            try {
                truth = gateway.chargesFor(due.reference());
            } catch (RuntimeException e) {
                // The provider cannot answer now: check again later.
                log.warn("Reconciliation of {} could not reach the provider", due.reference(), e);
                transactions.executeWithoutResult(tx -> attempts.findById(due.id()).orElseThrow()
                        .checkedWithoutChange(clock.instant()));
                continue;
            }
            transactions.executeWithoutResult(tx -> reconcile(attempts.findById(due.id()).orElseThrow(), truth));
        }
    }

    private void reconcile(PaymentAttempt attempt, List<RecordedCharge> truth) {
        Optional<String> charged = truth.stream()
                .filter(c -> c.result() instanceof PaymentGateway.Charged)
                .map(RecordedCharge::chargeId).findFirst();
        Optional<FailureReason> declined = truth.stream()
                .map(RecordedCharge::result)
                .filter(r -> r instanceof PaymentGateway.Declined)
                .map(r -> ((PaymentGateway.Declined) r).reason()).findFirst();

        if (charged.isPresent()) {
            AttemptState was = attempt.reconciledAsCharged(charged.get(), clock.instant());
            corrected(was, AttemptState.SUCCEEDED, attempt);
            events.publishEvent(new PaymentSucceeded(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                    attempt.reference(), attempt.amount(), charged.get()));
        } else if (attempt.state() == AttemptState.UNKNOWN) {
            // Declined, or never charged at all: either way the customer was not charged.
            FailureReason reason = declined.orElse(FailureReason.SOFT);
            attempt.reconciledAsFailed(reason, clock.instant());
            corrected(AttemptState.UNKNOWN, AttemptState.FAILED, attempt);
            events.publishEvent(new PaymentFailed(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                    attempt.reference(), attempt.amount(), reason));
        } else {
            attempt.checkedWithoutChange(clock.instant());
        }
        requests.settle(attempt);
    }

    private void corrected(AttemptState from, AttemptState to, PaymentAttempt attempt) {
        log.warn("Reconciliation corrected {} from {} to {}", attempt.reference(), from, to);
        meters.counter("tally.payment.outcome_corrected", "from", from.name(), "to", to.name()).increment();
    }
}
