package com.tally.payment.application;

import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.ChargeResult;
import com.tally.payment.domain.PaymentGateway.NotChargedException;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment's durable queue of attempts (FR-017), with its own worker pool (the bulkhead, FR-005), and the
 * worker that makes each attempt (design.md §5): ask Tally's own limits for a call, commit the attempt as
 * SENT, call the provider with no transaction open, then record the result and publish its event in a new
 * transaction.
 * <ul>
 *   <li>Held back by Tally (rate limit, open breaker): the provider is not called; the attempt stays
 *       REQUESTED and the item is retried.</li>
 *   <li>Provider error or refusal before charging: TECHNICAL_FAILURE, no event, and a new attempt after
 *       back-off (ADR-013).</li>
 *   <li>Anything else, such as a timeout: UNKNOWN, never retried; resolved by asking the provider.</li>
 * </ul>
 */
@Component
class CollectionWorker implements QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(CollectionWorker.class);

    // The permanent guard (FR-037, FR-041): one collection request per reference, never deleted. Each
    // item names one attempt of that request, sent only by its REQUESTED to SENT transition.
    static final QueueDefinition DEFINITION = QueueDefinition.of("payment-collection", "payment.collection_queue",
            "payment.collection_request_one_per_reference").asMoneyQueue();

    private final DurableQueue queue;
    private final PaymentAttempts attempts;
    private final CollectionRequestSettlement requests;
    private final ProviderIsolation provider;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CollectionWorker(DurableQueues queues, PaymentProperties properties, PaymentAttempts attempts,
            CollectionRequestSettlement requests, ProviderIsolation provider, ApplicationEventPublisher events,
            TransactionTemplate transactions, Clock clock) {
        this.attempts = attempts;
        this.requests = requests;
        this.provider = provider;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        // While the provider's circuit breaker is open, workers stop claiming (brief section 5).
        this.queue = queues.register(DEFINITION.withWorkers(properties.workers()), this, provider);
    }

    /** Queues an attempt, due at {@code dueAt}. Joins the caller's transaction. */
    void enqueue(UUID attemptId, Instant dueAt) {
        queue.enqueue(attemptId.toString(), dueAt, "");
    }

    /** The next try after a technical failure: a new attempt, due after the back-off. Caller's transaction. */
    void retry(PaymentAttempt failed, Instant dueAt) {
        PaymentAttempt next = failed.retry(clock.instant());
        attempts.add(next);
        enqueue(next.id(), dueAt);
    }

    @Override
    public Outcome handle(QueueItem item) {
        UUID attemptId = UUID.fromString(item.key());
        if (!provider.permit()) {
            // Held back by Tally: not sent, so still REQUESTED. Not a provider failure.
            return Outcome.retryAt(clock.instant().plus(ProviderIsolation.heldBackRetry()));
        }
        Integer claimed = transactions.execute(tx -> attempts.markSent(attemptId, clock.instant()));
        if (claimed == null || claimed == 0) {
            provider.release();
            // Already sent by another delivery, or final. A SENT attempt whose worker died is found by
            // reconciliation's stale rule (ADR-018).
            return Outcome.done();
        }
        PaymentAttempt attempt = attempts.find(attemptId).orElseThrow();
        ChargeResult result;
        try {
            result = provider.charge(attempt.paymentToken(), attempt.amount(), attempt.reference(),
                    attempt.idempotencyKey());
        } catch (NotChargedException e) {
            log.info("Attempt {} for {}: technical failure ({}); retried as a new attempt", attemptId,
                    attempt.reference(), e.why());
            transactions.executeWithoutResult(tx -> {
                PaymentAttempt failed = attempts.find(attemptId).orElseThrow();
                failed.technicalFailure(clock.instant());
                requests.settle(failed);
                retry(failed, clock.instant().plus(e.retryAfter()));
            });
            return Outcome.done();
        } catch (RuntimeException e) {
            log.warn("Attempt {} for {}: outcome unknown", attemptId, attempt.reference(), e);
            transactions.executeWithoutResult(tx -> attempts.find(attemptId).orElseThrow().timedOut(clock.instant()));
            return Outcome.done();
        }
        transactions.executeWithoutResult(tx -> record(attemptId, result));
        return Outcome.done();
    }

    private void record(UUID attemptId, ChargeResult result) {
        PaymentAttempt attempt = attempts.find(attemptId).orElseThrow();
        switch (result) {
            case PaymentGateway.Charged charged -> {
                attempt.succeeded(charged.chargeId(), clock.instant());
                Long sequence = requests.settle(attempt);
                events.publishEvent(new PaymentSucceeded(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                        attempt.reference(), attempt.amount(), charged.chargeId(), sequence));
            }
            case PaymentGateway.Declined declined -> {
                attempt.declined(declined.reason(), clock.instant());
                Long sequence = requests.settle(attempt);
                events.publishEvent(new PaymentFailed(UUID.randomUUID(), clock.instant(), attempt.accountId(),
                        attempt.reference(), attempt.amount(), declined.reason(), sequence));
            }
        }
    }
}
