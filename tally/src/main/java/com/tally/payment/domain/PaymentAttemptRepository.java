package com.tally.payment.domain;

import com.tally.payment.AttemptState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    boolean existsByReference(String reference);

    /** UNKNOWN and recently FAILED attempts due a check against the provider (ADR-018). */
    List<PaymentAttempt> findTop100ByReconcileAtLessThanEqualOrderByReconcileAt(Instant now);

    long countByState(AttemptState state);

    Optional<PaymentAttempt> findFirstByReferenceOrderByCreatedAtDesc(String reference);

    /** PENDING to IN_FLIGHT, only if still PENDING. Returns 1 if this caller won the transition. */
    @Modifying
    @Query("UPDATE PaymentAttempt a SET a.state = com.tally.payment.AttemptState.IN_FLIGHT, a.updatedAt = :now"
            + " WHERE a.id = :id AND a.state = com.tally.payment.AttemptState.PENDING")
    int markInFlight(UUID id, Instant now);
}
