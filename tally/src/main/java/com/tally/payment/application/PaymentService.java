package com.tally.payment.application;

import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentStatus;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttemptRepository;
import com.tally.shared.Money;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentService implements PaymentApi {

    private final PaymentAttemptRepository attempts;
    private final CollectionQueue queue;
    private final Clock clock;

    PaymentService(PaymentAttemptRepository attempts, CollectionQueue queue, Clock clock) {
        this.attempts = attempts;
        this.queue = queue;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void requestCollection(String reference, UUID accountId, Money amount, String paymentToken) {
        // Slice 1 makes one attempt per reference: no retries (brief section 1).
        if (attempts.existsByReference(reference)) {
            return;
        }
        PaymentAttempt attempt = attempts.save(
                PaymentAttempt.pending(reference, accountId, amount, paymentToken, clock.instant()));
        // Same transaction: the attempt and its queue item commit together.
        queue.enqueue(attempt.id());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentStatus> status(String reference) {
        return attempts.findFirstByReferenceOrderByCreatedAtDesc(reference)
                .map(a -> new PaymentStatus(a.reference(), a.state(), a.failureReason()));
    }
}
