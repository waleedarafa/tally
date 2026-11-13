package com.tally.accessfeed.domain;

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
}
