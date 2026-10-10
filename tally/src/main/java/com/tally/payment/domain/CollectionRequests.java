package com.tally.payment.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Payment's collection requests (a port; {@code adapter.persistence} implements it, F9). */
public interface CollectionRequests {

    /**
     * FR-041: creates the request for {@code reference} unless one exists, atomically. A concurrent
     * caller waits for the first insert to commit, then inserts nothing. Returns 1 if this call created it.
     */
    int insertIfAbsent(UUID id, String reference, UUID accountId, long amountMinor, String currency,
            String paymentToken, Instant now);

    Optional<CollectionRequest> findByReference(String reference);

    Optional<CollectionRequest> findById(UUID id);
}
