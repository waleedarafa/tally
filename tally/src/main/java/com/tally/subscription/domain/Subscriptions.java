package com.tally.subscription.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Subscription's subscriptions (a port; {@code adapter.persistence} implements it, F9). */
public interface Subscriptions {

    /**
     * Result of {@link #add}: {@code ADDED} if this row is new; {@code ALREADY_PRESENT} if this id already
     * exists (a same-key retry, ADR-045); {@code ACCOUNT_HAS_LIVE} only when the guard
     * {@code subscription_one_live_subscription_per_account} fires (CLAUDE.md rule 11). Any other failure
     * is thrown. After {@code ALREADY_PRESENT} or {@code ACCOUNT_HAS_LIVE} the caller's transaction cannot
     * continue.
     */
    enum Add {
        ADDED,
        ALREADY_PRESENT,
        ACCOUNT_HAS_LIVE
    }

    Add add(Subscription subscription);

    Optional<Subscription> find(UUID subscriptionId);

    boolean hasLive(UUID accountId);

    /**
     * The account's live subscriptions, each locked until the caller's transaction ends. Of two callers for one
     * account, the second waits, and then finds only the ones the first left live (CLAUDE.md rule 7).
     */
    List<Subscription> lockLive(UUID accountId);

    /** The subscription, locked until the caller's transaction ends; empty if there is no such subscription. */
    Optional<Subscription> lock(UUID subscriptionId);

    /**
     * Writes a changed subscription now, so its version is final and a concurrent change of the same
     * version fails here (optimistic lock).
     */
    void update(Subscription subscription);
}
