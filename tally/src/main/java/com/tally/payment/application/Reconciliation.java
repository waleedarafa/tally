package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.RecordedCharge;
import com.tally.platform.PeriodicJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation (FR-018; ADR-018; proposed ADR-026): asks the provider for the recorded truth of UNKNOWN
 * attempts, and of DECLINED attempts from the last 48 hours, on a backoff of 5 minutes, 1 hour, then
 * daily. It reads with no transaction open (FR-005), then records any correction and publishes the
 * matching event.
 * <ul>
 *   <li>First, the stale rule: a SENT attempt older than the call timeout has no worker left to record
 *       its result, so it becomes UNKNOWN (ADR-018; AT-15).</li>
 *   <li>A charge found for the reference decides: SUCCEEDED.</li>
 *   <li>A decline found decides an UNKNOWN attempt: DECLINED.</li>
 *   <li>Nothing found means no charge only when the provider's lookup is consistent and the attempt was
 *       sent longer ago than the call timeout plus the provider's stated delay: TECHNICAL_FAILURE, no
 *       event, and a new attempt. Otherwise the attempt stays UNKNOWN (CONTRACT.md A11, Q-B).</li>
 * </ul>
 * Every correction increments {@code tally.payment.outcome_corrected{from,to}}. A periodic job: runs
 * on one instance at a time and reports its health (FR-023).
 */
@Component
class Reconciliation implements PeriodicJob {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final PaymentAttempts attempts;
    private final CollectionRequestSettlement requests;
    private final CollectionWorker worker;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final PaymentProperties properties;
    private final Clock clock;

    Reconciliation(PaymentAttempts attempts, CollectionRequestSettlement requests, CollectionWorker worker,
            PaymentGateway gateway,
            ApplicationEventPublisher events, TransactionTemplate transactions, MeterRegistry meters,
            PaymentProperties properties, Clock clock) {
        this.attempts = attempts;
        this.requests = requests;
        this.worker = worker;
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
        return attempts.countIn(AttemptState.UNKNOWN);
    }

    @Override
    public void run() {
        Instant cutoff = clock.instant().minus(properties.provider().callTimeout());
        for (PaymentAttempt stale : attempts.sentBefore(cutoff)) {
            transactions.executeWithoutResult(tx -> {
                PaymentAttempt attempt = attempts.find(stale.id()).orElseThrow();
                if (attempt.state() == AttemptState.SENT) {
                    attempt.stale(clock.instant());
                    corrected(AttemptState.SENT, AttemptState.UNKNOWN, attempt);
                }
            });
        }
        for (PaymentAttempt due : attempts.dueForReconciliation(clock.instant(), 100)) {
            List<RecordedCharge> truth;
            try {
                truth = gateway.chargesFor(due.reference());
            } catch (RuntimeException e) {
                // The provider cannot answer now: check again later.
                log.warn("Reconciliation of {} could not reach the provider", due.reference(), e);
                transactions.executeWithoutResult(tx -> attempts.find(due.id()).orElseThrow()
                        .checkedWithoutChange(clock.instant()));
                continue;
            }
            transactions.executeWithoutResult(tx -> reconcile(attempts.find(due.id()).orElseThrow(), truth));
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
            AttemptState was = attempt.resolvedCharged(charged.get(), clock.instant());
            corrected(was, AttemptState.SUCCEEDED, attempt);
            events.publishEvent(new PaymentSucceeded(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                    attempt.reference(), attempt.amount(), charged.get()));
        } else if (attempt.state() == AttemptState.UNKNOWN && declined.isPresent()) {
            attempt.resolvedDeclined(declined.get(), clock.instant());
            corrected(AttemptState.UNKNOWN, AttemptState.DECLINED, attempt);
            events.publishEvent(new PaymentFailed(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                    attempt.reference(), attempt.amount(), declined.get()));
        } else if (attempt.state() == AttemptState.UNKNOWN && noChargeIsCertain(attempt)) {
            // Never charged: a technical failure, no event; the request is tried again (ADR-013).
            attempt.resolvedNoCharge(clock.instant());
            corrected(AttemptState.UNKNOWN, AttemptState.TECHNICAL_FAILURE, attempt);
            requests.settle(attempt);
            worker.retry(attempt, clock.instant());
            return;
        } else {
            attempt.checkedWithoutChange(clock.instant());
        }
        requests.settle(attempt);
    }

    /**
     * "Not found" means no charge only from a consistent lookup, and only once the charge would show:
     * sent longer ago than the call timeout plus the provider's stated delay. A lagging search read as
     * "no charge" would allow a second charge (CONTRACT.md A11, Q-B).
     */
    private boolean noChargeIsCertain(PaymentAttempt attempt) {
        PaymentProperties.Provider provider = properties.provider();
        Instant visibleBy = attempt.sentAt().orElse(Instant.MIN)
                .plus(provider.callTimeout()).plus(provider.lookupDelay());
        return provider.lookupConsistent() && !visibleBy.isAfter(clock.instant());
    }

    private void corrected(AttemptState from, AttemptState to, PaymentAttempt attempt) {
        log.warn("Reconciliation corrected {} from {} to {}", attempt.reference(), from, to);
        meters.counter("tally.payment.outcome_corrected", "from", from.name(), "to", to.name()).increment();
    }
}
