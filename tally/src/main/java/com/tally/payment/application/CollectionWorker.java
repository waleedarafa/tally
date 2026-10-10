package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttemptRepository;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.ChargeResult;
import com.tally.payment.domain.PaymentGateway.NotChargedException;
import com.tally.platform.Outcome;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes one collection attempt (FR-005): claim and commit, call the provider with no transaction
 * open, then record the result and publish its event in a new transaction. A call the provider
 * certainly did not charge goes back to the queue; any other failure leaves the outcome UNKNOWN,
 * never retried (FR-018).
 */
@Component
class CollectionWorker implements QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(CollectionWorker.class);

    private final PaymentAttemptRepository attempts;
    private final CollectionRequests requests;
    private final ProviderIsolation provider;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CollectionWorker(PaymentAttemptRepository attempts, CollectionRequests requests, ProviderIsolation provider,
            ApplicationEventPublisher events, TransactionTemplate transactions, Clock clock) {
        this.attempts = attempts;
        this.requests = requests;
        this.provider = provider;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Outcome handle(QueueItem item) {
        UUID attemptId = UUID.fromString(item.key());

        Integer claimed = transactions.execute(tx -> attempts.markInFlight(attemptId, clock.instant()));
        if (claimed == null || claimed == 0) {
            recoverAbandoned(attemptId);
            return Outcome.done();
        }

        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        ChargeResult result;
        try {
            result = provider.charge(attempt.paymentToken(), attempt.amount(), attempt.reference());
        } catch (NotChargedException e) {
            // Certainly not charged: rate limited, provider unavailable, or held back by the breaker.
            log.info("Attempt {} for {} not charged ({}); back to the queue", attemptId, attempt.reference(), e.why());
            transactions.executeWithoutResult(tx -> attempts.findById(attemptId).orElseThrow()
                    .returnToPending(clock.instant()));
            return Outcome.retryAt(clock.instant().plus(e.retryAfter()));
        } catch (RuntimeException e) {
            // The charge may have happened. Never retried in slice 1; reconciled (FR-018).
            log.warn("Attempt {} for {}: outcome unknown", attemptId, attempt.reference(), e);
            transactions.executeWithoutResult(tx -> {
                PaymentAttempt a = attempts.findById(attemptId).orElseThrow();
                a.unknown(clock.instant());
            });
            return Outcome.done();
        }
        transactions.executeWithoutResult(tx -> record(attemptId, result));
        return Outcome.done();
    }

    private void record(UUID attemptId, ChargeResult result) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        switch (result) {
            case PaymentGateway.Charged charged -> {
                attempt.succeeded(charged.chargeId(), clock.instant());
                events.publishEvent(new PaymentSucceeded(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                        attempt.reference(), attempt.amount(), charged.chargeId()));
            }
            case PaymentGateway.Declined declined -> {
                attempt.failed(declined.reason(), clock.instant());
                events.publishEvent(new PaymentFailed(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                        attempt.reference(), attempt.amount(), declined.reason()));
            }
        }
        requests.settle(attempt);
    }

    /**
     * The item came back without a PENDING attempt. If the attempt is IN_FLIGHT, a worker died
     * during the provider call: the charge may have happened, so it becomes UNKNOWN and is never
     * sent again (CLAUDE.md rule 9). Any other state is already final.
     */
    private void recoverAbandoned(UUID attemptId) {
        transactions.executeWithoutResult(tx -> attempts.findById(attemptId)
                .filter(a -> a.state() == AttemptState.IN_FLIGHT)
                .ifPresent(a -> {
                    log.warn("Attempt {} for {} was abandoned in flight: outcome unknown", attemptId, a.reference());
                    a.unknown(clock.instant());
                }));
    }
}
