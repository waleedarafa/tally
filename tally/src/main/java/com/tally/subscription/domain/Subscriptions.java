package com.tally.subscription.domain;

import java.util.Optional;
import java.util.UUID;

/** Subscription's subscriptions (a port; {@code adapter.persistence} implements it, F9). */
public interface Subscriptions {

    /** Adds a new subscription now; a concurrent one for the same account fails the unique constraint. */
    void add(Subscription subscription);

    Optional<Subscription> find(UUID subscriptionId);

    boolean existsFor(UUID accountId);

    /**
     * Writes a changed subscription now, so its version is final and a concurrent change of the same
     * version fails here (optimistic lock).
     */
    void update(Subscription subscription);
}
