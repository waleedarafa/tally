package com.tally.payment.adapter.persistence;

import com.tally.payment.domain.CollectionRequest;
import com.tally.payment.domain.CollectionRequests;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements {@link CollectionRequests}; its method names stay here (CLAUDE.md rule 14). */
interface JpaCollectionRequests extends JpaRepository<CollectionRequest, UUID>, CollectionRequests {

    @Override
    default boolean addIfAbsent(UUID id, String reference, UUID accountId, long amountMinor, String currency,
            String paymentToken, Instant now) {
        return insertIfAbsent(id, reference, accountId, amountMinor, currency, paymentToken, now) == 1;
    }

    @Override
    default Optional<CollectionRequest> forReference(String reference) {
        return findByReference(reference);
    }

    @Override
    default Optional<CollectionRequest> find(UUID id) {
        return findById(id);
    }

    Optional<CollectionRequest> findByReference(String reference);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO payment.collection_request
                (id, reference, account_id, amount_minor, currency, payment_token, outcome, created_at, updated_at)
            VALUES (:id, :reference, :accountId, :amountMinor, :currency, :paymentToken, 'OPEN', :now, :now)
            ON CONFLICT ON CONSTRAINT collection_request_one_per_reference DO NOTHING
            """)
    int insertIfAbsent(UUID id, String reference, UUID accountId, long amountMinor, String currency,
            String paymentToken, Instant now);
}
