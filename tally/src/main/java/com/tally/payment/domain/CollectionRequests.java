package com.tally.payment.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Payment's collection requests (a port; {@code adapter.persistence} implements it, F9). */
public interface CollectionRequests {

    /**
     * FR-041: adds the request for {@code reference} unless one exists, atomically. A concurrent caller
     * waits for the first insert to commit, then adds nothing. Only the reference counts as a duplicate
     * (CLAUDE.md rule 11). Returns whether this call added it.
     */
    boolean addIfAbsent(UUID id, String reference, UUID accountId, long amountMinor, String currency,
            String paymentToken, Instant now);

    Optional<CollectionRequest> forReference(String reference);

    Optional<CollectionRequest> find(UUID id);
}
