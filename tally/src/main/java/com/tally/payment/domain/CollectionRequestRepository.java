package com.tally.payment.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CollectionRequestRepository extends JpaRepository<CollectionRequest, UUID> {

    /**
     * FR-041: creates the request for {@code reference} unless one exists, atomically. A concurrent
     * caller waits for the first insert to commit, then inserts nothing. Returns 1 if this call created it.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO payment.collection_request
                (id, reference, account_id, amount_minor, currency, payment_token, outcome, created_at, updated_at)
            VALUES (:id, :reference, :accountId, :amountMinor, :currency, :paymentToken, 'OPEN', :now, :now)
            ON CONFLICT (reference) DO NOTHING
            """)
    int insertIfAbsent(UUID id, String reference, UUID accountId, long amountMinor, String currency,
            String paymentToken, Instant now);

    Optional<CollectionRequest> findByReference(String reference);
}
