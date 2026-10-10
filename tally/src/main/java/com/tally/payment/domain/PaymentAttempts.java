package com.tally.payment.domain;

import com.tally.payment.AttemptState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payment's attempts (a port; {@code adapter.persistence} implements it, F9). */
public interface PaymentAttempts {

    Optional<PaymentAttempt> findById(UUID id);

    PaymentAttempt save(PaymentAttempt attempt);

    /** UNKNOWN and recently FAILED attempts due a check against the provider (FR-018). */
    List<PaymentAttempt> findTop100ByReconcileAtLessThanEqualOrderByReconcileAt(Instant now);

    long countByState(AttemptState state);

    Optional<PaymentAttempt> findFirstByReferenceOrderByCreatedAtDesc(String reference);

    List<PaymentAttempt> findByReferenceInAndState(Collection<String> references, AttemptState state);

    /** PENDING to IN_FLIGHT, only if still PENDING. Returns 1 if this caller won the transition. */
    int markInFlight(UUID id, Instant now);
}
