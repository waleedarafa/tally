package com.tally.platform;

import java.time.Instant;

/** A registered queue. Pollers on every instance claim its due items (ADR-017). */
public interface DurableQueue {

    String name();

    /**
     * Adds an item, due at {@code dueAt}. Idempotent per key: returns {@code false}, and changes
     * nothing, if an item with this key already exists.
     */
    boolean enqueue(String key, Instant dueAt, String payload);

    /**
     * Claims one batch of due items, commits the claim, then runs the handler on the calling
     * thread. Returns the number of items handled. Pollers call this; so can tests.
     *
     * @throws IllegalStateException if called inside a transaction (ADR-005)
     */
    int processDue();
}
