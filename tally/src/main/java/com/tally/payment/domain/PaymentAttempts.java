package com.tally.payment.domain;

import com.tally.payment.AttemptState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payment's attempts (a port; {@code adapter.persistence} implements it, F9). */
public interface PaymentAttempts {

    Optional<PaymentAttempt> find(UUID id);

    /**
     * Adds a new attempt. Writes pending changes to other attempts first, so a retry's predecessor has
     * left the open states before the new one counts against "one open attempt per reference" (P2).
     */
    void add(PaymentAttempt attempt);

    /** UNKNOWN and recently DECLINED attempts due a check against the provider (FR-018), oldest first. */
    List<PaymentAttempt> dueForReconciliation(Instant now, int limit);

    /** SENT before {@code cutoff}: their call has timed out without a result (ADR-018). */
    List<PaymentAttempt> sentBefore(Instant cutoff);

    long countIn(AttemptState state);

    /** The attempt with the highest number for the reference. */
    Optional<PaymentAttempt> latestFor(String reference);

    List<PaymentAttempt> inStateFor(Collection<String> references, AttemptState state);

    /** REQUESTED to SENT, only if still REQUESTED: the claim before the call. Returns 1 if this caller won. */
    int markSent(UUID id, Instant now);
}
