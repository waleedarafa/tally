package com.tally.subscription.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Subscription's subscriptions (a port; {@code adapter.persistence} implements it, F9). */
public interface Subscriptions {

    /**
     * Adds a new live subscription now, unless the account already has one. Returns false, and adds nothing, only
     * when the guard {@code subscription_one_live_per_account} fires, as it does for a concurrent sign-up of the same
     * account (CLAUDE.md rule 11); any other failure is thrown. The caller's transaction cannot continue after false.
     */
    boolean addIfNoneLive(Subscription subscription);

    Optional<Subscription> find(UUID subscriptionId);

    boolean hasLive(UUID accountId);

    /**
     * The account's live subscriptions, each locked until the caller's transaction ends. Of two callers for one
     * account, the second waits, and then finds only the ones the first left live (CLAUDE.md rule 7).
     */
    List<Subscription> lockLive(UUID accountId);

    /**
     * Writes a changed subscription now, so its version is final and a concurrent change of the same
     * version fails here (optimistic lock).
     */
    void update(Subscription subscription);
}
