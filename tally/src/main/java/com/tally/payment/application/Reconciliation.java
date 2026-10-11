package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation (FR-018; ADR-018; ADR-026): asks the provider for the recorded truth of UNKNOWN
 * attempts, and of DECLINED attempts from the last 48 hours, on a backoff of 5 minutes, 1 hour, then
 * daily. It reads with no transaction open (FR-005), then records any correction and publishes the
 * matching event.
 * <ul>
 *   <li>First, the stale rule: a SENT attempt older than the call timeout has no worker left to record
 *       its result, so it becomes UNKNOWN (ADR-018; AT-15).</li>
 *   <li>Then each attempt due, by {@link #check}: (a) the lookup by reference; (b) within the key window,
 *       a repeat with the attempt's key; (c) past it, never a repeat (ADR-026). An UNKNOWN
 *       attempt is never retried as a new attempt: it is the same charge, resolved or left for a person.</li>
 * </ul>
 * Every correction goes through {@link Corrections}. A periodic job: runs
 * on one instance at a time and reports its health (FR-023).
 */
@Component
class Reconciliation implements PeriodicJob {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final PaymentAttempts attempts;
    private final Corrections corrections;
    private final PaymentGateway gateway;
    private final TransactionTemplate transactions;
    private final PaymentProperties properties;
    private final Clock clock;

    Reconciliation(PaymentAttempts attempts, Corrections corrections, PaymentGateway gateway,
            TransactionTemplate transactions, MeterRegistry meters, PaymentProperties properties, Clock clock) {
        this.attempts = attempts;
        this.corrections = corrections;
        this.gateway = gateway;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder("tally.payment.unknown_past_key_window", attempts,
                        a -> a.countUnknownSentBefore(clock.instant().minus(properties.provider().keyRepeatWindow())))
                .description("UNKNOWN attempts past the key window: never repeated, they wait for settlement"
                        + " reconciliation and a person (ADR-026 c). Runbook:"
                        + " docs/runbooks/unknown-payment-past-key-window.md")
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
                    corrections.corrected(AttemptState.SENT, AttemptState.UNKNOWN, attempt);
                }
            });
        }
        for (PaymentAttempt due : attempts.dueForReconciliation(clock.instant(), 100)) {
            check(due);
        }
    }

    /**
     * Resolves one attempt, in the order of ADR-026, with no transaction open during any call to
     * the provider (FR-005): (a) look the reference up; for the latest attempt of the reference, a charge
     * found decides, and a decline found decides an UNKNOWN first attempt. An earlier attempt learns nothing
     * from the lookup, which also shows its successors' charges: a superseded decline stops being checked,
     * and an UNKNOWN one goes on to (b). (b) An UNKNOWN attempt with nothing found, whose key is inside the repeat
     * window: repeat its charge with the same key and stored parameters; the replay or fresh outcome decides, and
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

        // Read after the lookup: an attempt is saved before it is sent (FR-005), so every charge the lookup
        // shows belongs to an attempt this read sees. The lookup is by reference and shows every attempt's
        // charges, so it decides only for the latest attempt.
        boolean latest = attempts.latestFor(due.reference()).map(a -> a.id().equals(due.id())).orElse(false);

        if (!latest && due.state() == AttemptState.DECLINED) {
            // A later attempt settles the reference; this decline is history (AT-16).
            transactions.executeWithoutResult(tx -> attempts.find(due.id()).orElseThrow().superseded());
        } else if (latest && charged.isPresent()) {
            transactions.executeWithoutResult(tx -> corrections.charged(attempts.find(due.id()).orElseThrow(),
                    charged.get()));
        } else if (latest && due.state() == AttemptState.UNKNOWN && due.number() == 1 && declined.isPresent()) {
            // Only the first attempt: a later one's lookup also shows its predecessors' declines. A later
            // attempt learns its own outcome by repeating with its key, below.
            transactions.executeWithoutResult(tx -> corrections.declined(attempts.find(due.id()).orElseThrow(),
                    declined.get()));
        } else if (due.state() == AttemptState.UNKNOWN
                && due.mayRepeatKey(properties.provider().keyRepeatWindow(), clock.instant())) {
            repeat(due);
        } else {
            if (due.state() == AttemptState.UNKNOWN) {
                log.error("Attempt {} for {} is UNKNOWN past the key window: never repeated; settlement"
                        + " reconciliation decides (ADR-026 c)", due.id(), due.reference());
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
                case PaymentGateway.Charged c -> corrections.charged(attempt, c.chargeId());
                case PaymentGateway.Declined d -> corrections.declined(attempt, d.reason());
            }
        });
    }

    private void checkedWithoutChange(PaymentAttempt due) {
        transactions.executeWithoutResult(tx -> attempts.find(due.id()).orElseThrow()
                .checkedWithoutChange(clock.instant()));
    }

}
