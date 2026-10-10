package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentStatus;
import com.tally.payment.domain.CollectionRequest;
import com.tally.payment.domain.CollectionRequests;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.domain.PaymentGateway.RecordedCharge;
import com.tally.shared.Money;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PaymentService implements PaymentApi {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final CollectionRequests requests;
    private final PaymentAttempts attempts;
    private final CollectionWorker worker;
    private final CollectionRequestSettlement settlement;
    private final Corrections corrections;
    private final PaymentGateway gateway;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PaymentService(CollectionRequests requests, PaymentAttempts attempts, CollectionWorker worker,
            CollectionRequestSettlement settlement, Corrections corrections, PaymentGateway gateway,
            TransactionTemplate transactions, Clock clock) {
        this.requests = requests;
        this.attempts = attempts;
        this.worker = worker;
        this.settlement = settlement;
        this.corrections = corrections;
        this.gateway = gateway;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * FR-041: an atomic insert-or-return. The first call creates the collection request, its first
     * attempt and the queue item, in one transaction. Any later or concurrent call for the same
     * reference finds the request and creates nothing: it neither fails nor charges twice.
     */
    @Override
    @Transactional
    public void requestCollection(String reference, UUID accountId, Money amount, String paymentToken) {
        UUID requestId = UUID.randomUUID();
        boolean created = requests.addIfAbsent(requestId, reference, accountId, amount.minorUnits(),
                amount.currency().getCurrencyCode(), paymentToken, clock.instant());
        if (!created) {
            requests.forReference(reference)
                    .filter(existing -> !existing.amount().equals(amount) || !existing.accountId().equals(accountId))
                    .ifPresent(existing -> log.warn("Collection for {} requested again with {} for account {};"
                                    + " the first request ({} for account {}) stands", reference, amount, accountId,
                            existing.amount(), existing.accountId()));
            return;
        }
        PaymentAttempt attempt = PaymentAttempt.requested(requestId, reference, accountId, amount, paymentToken,
                clock.instant());
        attempts.add(attempt);
        // Same transaction: the request, its attempt and the queue item commit together.
        worker.enqueue(attempt.id(), clock.instant());
    }

    /**
     * Reads, then asks the provider with no transaction open, then decides in one transaction that
     * re-reads the request and its latest attempt: a concurrent change in between is seen, and two
     * concurrent retries collide on the request's optimistic lock.
     */
    @Override
    public void collectAgain(String reference, int retry) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("collectAgain asks the provider: call it with no transaction open"
                    + " (CLAUDE.md rule 6)");
        }
        Optional<CollectionRequest> request = requests.forReference(reference);
        if (request.isEmpty()) {
            log.warn("Retry {} of {}: no collection was requested for this reference", retry, reference);
            return;
        }
        if (request.get().retries() >= retry) {
            return;
        }
        if (attempts.latestFor(reference).map(PaymentAttempt::state).orElse(null) != AttemptState.DECLINED) {
            // Open (REQUESTED, SENT, UNKNOWN), paid, or a technical failure whose retry is already queued.
            log.info("Retry {} of {}: the latest attempt is not declined; nothing to retry", retry, reference);
            return;
        }
        Optional<String> charged = gateway.chargesFor(reference).stream()
                .filter(c -> c.result() instanceof PaymentGateway.Charged)
                .map(RecordedCharge::chargeId).findFirst();
        transactions.executeWithoutResult(tx -> {
            CollectionRequest current = requests.forReference(reference).orElseThrow();
            PaymentAttempt latest = attempts.latestFor(reference).orElseThrow();
            if (latest.state() != AttemptState.DECLINED || !current.retryRequested(retry, clock.instant())) {
                return;
            }
            if (charged.isPresent()) {
                // The provider charged, whatever it answered: correct the decline, make no new attempt.
                corrections.charged(latest, charged.get());
            } else {
                PaymentAttempt next = latest.retry(clock.instant());
                attempts.add(next);
                settlement.settle(next);
                worker.enqueue(next.id(), clock.instant());
            }
            requests.update(current);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentStatus> status(String reference) {
        return attempts.latestFor(reference)
                .map(a -> new PaymentStatus(a.reference(), a.state(), a.failureReason()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Money> collected(Collection<String> references) {
        return attempts.inStateFor(references, AttemptState.SUCCEEDED).stream()
                .collect(Collectors.toMap(PaymentAttempt::reference, PaymentAttempt::amount));
    }
}
