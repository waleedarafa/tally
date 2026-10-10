package com.tally.payment.application;

import com.tally.payment.AttemptState;
import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentStatus;
import com.tally.payment.domain.CollectionRequests;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
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

@Service
class PaymentService implements PaymentApi {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final CollectionRequests requests;
    private final PaymentAttempts attempts;
    private final CollectionQueue queue;
    private final Clock clock;

    PaymentService(CollectionRequests requests, PaymentAttempts attempts, CollectionQueue queue,
            Clock clock) {
        this.requests = requests;
        this.attempts = attempts;
        this.queue = queue;
        this.clock = clock;
    }

    /**
     * FR-041: an atomic insert-or-return. The first call creates the collection request, its one
     * attempt and the queue item, in one transaction. Any later or concurrent call for the same
     * reference finds the request and creates nothing: it neither fails nor charges twice.
     */
    @Override
    @Transactional
    public void requestCollection(String reference, UUID accountId, Money amount, String paymentToken) {
        UUID requestId = UUID.randomUUID();
        int created = requests.insertIfAbsent(requestId, reference, accountId, amount.minorUnits(),
                amount.currency().getCurrencyCode(), paymentToken, clock.instant());
        if (created == 0) {
            requests.findByReference(reference)
                    .filter(existing -> !existing.amount().equals(amount) || !existing.accountId().equals(accountId))
                    .ifPresent(existing -> log.warn("Collection for {} requested again with {} for account {};"
                                    + " the first request ({} for account {}) stands", reference, amount, accountId,
                            existing.amount(), existing.accountId()));
            return;
        }
        PaymentAttempt attempt = attempts.save(
                PaymentAttempt.pending(requestId, reference, accountId, amount, paymentToken, clock.instant()));
        // Same transaction: the request, its attempt and the queue item commit together.
        queue.enqueue(attempt.id());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentStatus> status(String reference) {
        return attempts.findFirstByReferenceOrderByCreatedAtDesc(reference)
                .map(a -> new PaymentStatus(a.reference(), a.state(), a.failureReason()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Money> collected(Collection<String> references) {
        return attempts.findByReferenceInAndState(references, AttemptState.SUCCEEDED).stream()
                .collect(Collectors.toMap(PaymentAttempt::reference, PaymentAttempt::amount));
    }
}
