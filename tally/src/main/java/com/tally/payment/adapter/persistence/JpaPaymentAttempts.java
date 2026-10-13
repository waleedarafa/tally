package com.tally.payment.adapter.persistence;

import com.tally.payment.AttemptState;
import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements {@link PaymentAttempts}; its method names stay here (CLAUDE.md rule 14). */
interface JpaPaymentAttempts extends JpaRepository<PaymentAttempt, UUID>, PaymentAttempts {

    @Override
    default Optional<PaymentAttempt> find(UUID id) {
        return findById(id);
    }

    @Override
    default void add(PaymentAttempt attempt) {
        flush();
        save(attempt);
        flush();
    }

    @Override
    default List<PaymentAttempt> dueForReconciliation(Instant now, int limit) {
        return findByReconcileAtLessThanEqualOrderByReconcileAt(now, Limit.of(limit));
    }

    @Override
    default List<PaymentAttempt> sentBefore(Instant cutoff) {
        return findByStateAndSentAtBefore(AttemptState.SENT, cutoff);
    }

    @Override
    default long countIn(AttemptState state) {
        return countByState(state);
    }

    @Override
    default long countUnknownSentBefore(Instant cutoff) {
        return countByStateAndSentAtBefore(AttemptState.UNKNOWN, cutoff);
    }

    @Override
    default Optional<Instant> oldestUnknownSentAt() {
        return Optional.ofNullable(findOldestSentAtInState(AttemptState.UNKNOWN));
    }

    @Override
    default Optional<Instant> oldestWaitingToBeSent() {
        return Optional.ofNullable(findOldestCreatedAtInState(AttemptState.REQUESTED));
    }

    @Override
    default Optional<PaymentAttempt> latestFor(String reference) {
        return findFirstByReferenceOrderByNumberDesc(reference);
    }

    @Override
    default List<PaymentAttempt> inStateFor(Collection<String> references, AttemptState state) {
        return findByReferenceInAndState(references, state);
    }

    List<PaymentAttempt> findByReconcileAtLessThanEqualOrderByReconcileAt(Instant now, Limit limit);

    List<PaymentAttempt> findByStateAndSentAtBefore(AttemptState state, Instant cutoff);

    long countByState(AttemptState state);

    long countByStateAndSentAtBefore(AttemptState state, Instant cutoff);

    @Query("SELECT min(a.createdAt) FROM PaymentAttempt a WHERE a.state = :state")
    Instant findOldestCreatedAtInState(AttemptState state);

    @Query("SELECT min(a.sentAt) FROM PaymentAttempt a WHERE a.state = :state")
    Instant findOldestSentAtInState(AttemptState state);

    Optional<PaymentAttempt> findFirstByReferenceOrderByNumberDesc(String reference);

    List<PaymentAttempt> findByReferenceInAndState(Collection<String> references, AttemptState state);

    @Override
    @Modifying
    @Query("UPDATE PaymentAttempt a SET a.state = com.tally.payment.AttemptState.SENT, a.sentAt = :now,"
            + " a.updatedAt = :now WHERE a.id = :id AND a.state = com.tally.payment.AttemptState.REQUESTED")
    int markSent(UUID id, Instant now);
}
