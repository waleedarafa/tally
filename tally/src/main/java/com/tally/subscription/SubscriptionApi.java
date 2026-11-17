package com.tally.subscription;

import java.util.List;
import java.util.UUID;

/** Subscriptions, their periods and renewals (Design Record §12: Subscription owns the billing calendar). */
public interface SubscriptionApi {

    /**
     * Starts a subscription and publishes {@link SubscriptionChanged} of type STARTED.
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     * @throws com.tally.offers.UnknownPlanException if the plan cannot be sold to the account
     * @throws AlreadySubscribedException if the account already has a live subscription (one live subscription per account)
     */
    SubscriptionView subscribe(UUID accountId, String planId);

    /**
     * Ends the account's live subscriptions (design.md §5: any live state to ENDED), and publishes
     * {@link SubscriptionChanged} of type ENDED for each. Returns the ones it ended: none if the account has none
     * live, so a repeated or concurrent end changes nothing (CLAUDE.md rule 7). A renewal that waits for an ended
     * subscription runs and does nothing.
     */
    List<UUID> endLiveSubscriptions(UUID accountId, EndReason reason);

    /**
     * Ends this subscription if it is live, and publishes {@link SubscriptionChanged} of type ENDED. Returns whether
     * this call ended it: false if it has ended already or does not exist, so a repeated end changes nothing. Ends no
     * other subscription of the account (Slice 3 PR-7: a late repeat of an old decline never ends a newer one).
     */
    boolean end(UUID subscriptionId, EndReason reason);

    /** Every change to the subscription, oldest first. Empty for an unknown subscription. */
    List<TimelineEntry> timeline(UUID subscriptionId);
}
