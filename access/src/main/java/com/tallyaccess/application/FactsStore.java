package com.tallyaccess.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Access's copy and its position in the log, in its own database (ADR-001, ADR-035). */
public interface FactsStore {

    Optional<StoredFacts> find(UUID accountId);

    /**
     * Stores the message's facts, unless the account's stored facts have this sequence or a higher one: one atomic
     * statement, insert or update only if the stored sequence is lower (brief PR-6; CLAUDE.md rule 15).
     *
     * @return whether the facts were stored
     */
    boolean applyIfNewer(AccessFactsMessage message, Instant appliedAt);

    /** The next offset to read, per partition of the topic; none for a partition Access has not read. */
    Map<Integer, Long> positions(String topic);

    /** Records the next offset to read; never moves a position back (two instances may hold a partition briefly). */
    void advance(String topic, int partition, long nextOffset);

    /** Whether the copy once reached the end of the log an instance saw at start (ADR-036). */
    boolean completeOnce();

    /** Records that the copy reached that end; a second mark changes nothing. */
    void markComplete(Instant at);

    /** When the newest facts Access has were produced, if it has any. */
    Optional<Instant> newestProducedAt();
}
