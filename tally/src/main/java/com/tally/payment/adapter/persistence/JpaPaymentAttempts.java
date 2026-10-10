package com.tally.payment.adapter.persistence;

import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements {@link PaymentAttempts}. */
interface JpaPaymentAttempts extends JpaRepository<PaymentAttempt, UUID>, PaymentAttempts {

    @Override
    @Modifying
    @Query("UPDATE PaymentAttempt a SET a.state = com.tally.payment.AttemptState.IN_FLIGHT, a.updatedAt = :now"
            + " WHERE a.id = :id AND a.state = com.tally.payment.AttemptState.PENDING")
    int markInFlight(UUID id, Instant now);
}
