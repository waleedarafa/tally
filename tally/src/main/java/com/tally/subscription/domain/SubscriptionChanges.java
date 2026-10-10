package com.tally.subscription.domain;

import java.util.List;
import java.util.UUID;

/** A subscription's history of changes (a port; {@code adapter.persistence} implements it, F9). */
public interface SubscriptionChanges {

    void add(SubscriptionChange change);

    /** Oldest first. */
    List<SubscriptionChange> historyOf(UUID subscriptionId);
}
