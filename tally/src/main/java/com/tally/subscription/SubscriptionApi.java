package com.tally.subscription;

import java.util.List;
import java.util.UUID;

/** Subscriptions record business changes only; Invoicing owns the billing calendar (FR-002). */
public interface SubscriptionApi {

    /**
     * Starts a subscription and publishes {@link SubscriptionChanged} of type STARTED.
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     * @throws com.tally.catalog.UnknownPlanException if the plan cannot be sold to the account
     * @throws AlreadySubscribedException if the account already has a subscription (one per account in slice 1)
     */
    SubscriptionView subscribe(UUID accountId, String planId);

    /** Every change to the subscription, oldest first. Empty for an unknown subscription. */
    List<TimelineEntry> timeline(UUID subscriptionId);
}
