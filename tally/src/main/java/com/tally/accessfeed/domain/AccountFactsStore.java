package com.tally.accessfeed.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Where the feed keeps its facts: one row per account, changed under that row's lock. */
public interface AccountFactsStore {

    /**
     * The account's facts, created if the feed has none yet, and locked until the caller's transaction ends: of two
     * callers for one account, the second waits for the first (CLAUDE.md rule 7: an atomic insert, then a lock,
     * never "check, then act").
     */
    AccountFacts lockOrCreate(UUID accountId);

    /** Writes the facts locked by {@link #lockOrCreate} in this transaction. */
    void save(AccountFacts facts);

    /** The last sequence that reached the event log; 0 if none. Read under the lock of {@link #lockOrCreate}. */
    long sentSequence(UUID accountId);

    /** Records that {@code sequence} reached the event log. Never lowers it. */
    void recordSent(UUID accountId, long sequence);

    /** When the oldest facts not yet in the log were produced, if any facts are not. */
    Optional<Instant> oldestUnsent();
}
