package com.tally.subscription.domain;

import java.util.List;
import java.util.UUID;

/** A subscription's history of changes (a port; {@code adapter.persistence} implements it, F9). */
public interface SubscriptionChanges {

    SubscriptionChange save(SubscriptionChange change);

    List<SubscriptionChange> findBySubscriptionIdOrderByVersion(UUID subscriptionId);
}
