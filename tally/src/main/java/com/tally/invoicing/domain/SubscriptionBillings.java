package com.tally.invoicing.domain;

import java.util.Optional;
import java.util.UUID;

/** The subscriptions Invoicing bills (a port; {@code adapter.persistence} implements it, F9). */
public interface SubscriptionBillings {

    /**
     * Records the subscription unless it is already recorded, atomically: a concurrent duplicate
     * delivery waits for the first insert to commit, then inserts nothing (FR-028).
     */
    void insertIfAbsent(SubscriptionBilling billing);

    Optional<SubscriptionBilling> findById(UUID subscriptionId);
}
