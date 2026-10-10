package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.ChargeResult;
import com.tally.payment.domain.PaymentGateway.RecordedCharge;
import com.tally.platform.PeriodicJob;
import io.micrometer.core.instrument.Gauge;
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
 *   <li>Then each attempt due, by {@link #check}: (a) the lookup by reference; (b) within the key window,
 *       a repeat with the attempt's key; (c) past it, never a repeat (proposed ADR-026). An UNKNOWN
 *       attempt is never retried as a new attempt: it is the same charge, resolved or left for a person.</li>
 * </ul>
 * Every correction increments {@code tally.payment.outcome_corrected{from,to}}. A periodic job: runs
 * on one instance at a time and reports its health (FR-023).
 */
@Component
class Reconciliation implements PeriodicJob {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final PaymentAttempts attempts;
    private final CollectionRequestSettlement requests;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final PaymentProperties properties;
    private final Clock clock;

    Reconciliation(PaymentAttempts attempts, CollectionRequestSettlement requests, PaymentGateway gateway,
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
        Gauge.builder("tally.payment.unknown_past_key_window", attempts,
                        a -> a.countUnknownSentBefore(clock.instant().minus(properties.provider().keyRepeatWindow())))
                .description("UNKNOWN attempts past the key window: never repeated, they wait for settlement"
                        + " reconciliation and a person (proposed ADR-026 c)")
                .strongReference(true).register(meters);
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
            check(due);
        }
    }

    /**
     * Resolves one attempt, in the order of proposed ADR-026, with no transaction open during any call to
     * the provider (FR-005): (a) look the reference up; a charge found decides, and a decline found decides
     * an UNKNOWN attempt. (b) An UNKNOWN attempt with nothing found, whose key is inside the repeat window:
     * repeat its charge with the same key and stored parameters; the replay or fresh outcome decides, and
     * no answer (a refusal, a 409, a timeout) leaves it UNKNOWN for a later check. (c) Past the window:
     * never repeat; it stays UNKNOWN, counted in {@code tally.payment.unknown_past_key_window}.
     */
    void check(PaymentAttempt due) {
        List<RecordedCharge> truth;
        try {
            truth = gateway.chargesFor(due.reference());
        } catch (RuntimeException e) {
            // The provider cannot answer now: check again later.
            log.warn("Reconciliation of {} could not reach the provider", due.reference(), e);
            checkedWithoutChange(due);
            return;
        }
        Optional<String> charged = truth.stream()
                .filter(c -> c.result() instanceof PaymentGateway.Charged)
                .map(RecordedCharge::chargeId).findFirst();
        Optional<FailureReason> declined = truth.stream()
                .map(RecordedCharge::result)
                .filter(r -> r instanceof PaymentGateway.Declined)
                .map(r -> ((PaymentGateway.Declined) r).reason()).findFirst();

        if (charged.isPresent()) {
            transactions.executeWithoutResult(tx -> resolvedCharged(attempts.find(due.id()).orElseThrow(),
                    charged.get()));
        } else if (due.state() == AttemptState.UNKNOWN && declined.isPresent()) {
            transactions.executeWithoutResult(tx -> resolvedDeclined(attempts.find(due.id()).orElseThrow(),
                    declined.get()));
        } else if (due.state() == AttemptState.UNKNOWN
                && due.mayRepeatKey(properties.provider().keyRepeatWindow(), clock.instant())) {
            repeat(due);
        } else {
            if (due.state() == AttemptState.UNKNOWN) {
                log.error("Attempt {} for {} is UNKNOWN past the key window: never repeated; settlement"
                        + " reconciliation decides (proposed ADR-026 c)", due.id(), due.reference());
            }
            checkedWithoutChange(due);
        }
    }

    /** (b) The repeat is the lookup by key (CONTRACT.md A23): the same key, the same parameters. */
    private void repeat(PaymentAttempt due) {
        ChargeResult result;
        try {
            result = gateway.charge(due.paymentToken(), due.amount(), due.reference(), due.idempotencyKey());
        } catch (RuntimeException e) {
            log.warn("Repeat of attempt {} for {} by key got no answer; checked again later", due.id(),
                    due.reference(), e);
            checkedWithoutChange(due);
            return;
        }
        transactions.executeWithoutResult(tx -> {
            PaymentAttempt attempt = attempts.find(due.id()).orElseThrow();
            if (attempt.state() != AttemptState.UNKNOWN) {
                return;
            }
            switch (result) {
                case PaymentGateway.Charged c -> resolvedCharged(attempt, c.chargeId());
                case PaymentGateway.Declined d -> resolvedDeclined(attempt, d.reason());
            }
        });
    }

    private void resolvedCharged(PaymentAttempt attempt, String chargeId) {
        AttemptState was = attempt.resolvedCharged(chargeId, clock.instant());
        corrected(was, AttemptState.SUCCEEDED, attempt);
        Long sequence = requests.settle(attempt);
        events.publishEvent(new PaymentSucceeded(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                attempt.reference(), attempt.amount(), chargeId, sequence));
    }

    private void resolvedDeclined(PaymentAttempt attempt, FailureReason reason) {
        attempt.resolvedDeclined(reason, clock.instant());
        corrected(AttemptState.UNKNOWN, AttemptState.DECLINED, attempt);
        Long sequence = requests.settle(attempt);
        events.publishEvent(new PaymentFailed(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                attempt.reference(), attempt.amount(), reason, sequence));
    }

    private void checkedWithoutChange(PaymentAttempt due) {
        transactions.executeWithoutResult(tx -> attempts.find(due.id()).orElseThrow()
                .checkedWithoutChange(clock.instant()));
    }

    private void corrected(AttemptState from, AttemptState to, PaymentAttempt attempt) {
        log.warn("Reconciliation corrected {} from {} to {}", attempt.reference(), from, to);
        meters.counter("tally.payment.outcome_corrected", "from", from.name(), "to", to.name()).increment();
    }
}
