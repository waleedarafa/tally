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
     * Starts a subscription in TRIAL (design.md §5): same guards as {@link #subscribe}, with period 1 from the local day it
     * starts, and it publishes STARTED like any start. The trial ends into ACTIVE via {@link #endTrial}.
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     * @throws com.tally.offers.UnknownPlanException if the plan cannot be sold to the account
     * @throws AlreadySubscribedException if the account already has a live subscription (one live subscription per account)
     */
    SubscriptionView startTrial(UUID accountId, String planId);

    /**
     * Trial conversion: TRIAL → ACTIVE (design.md §5). Publishes no change: there is no change type for the trial
     * ending. Returns whether this call converted the subscription: false if it is not in TRIAL, so a repeated call
     * changes nothing.
     */
    boolean endTrial(UUID subscriptionId);

    /**
     * A cancel requested: TRIAL or ACTIVE → CANCELLING (design.md §5); access ends when the current period ends.
     * Publishes {@link SubscriptionChanged} of type CANCELLED. Returns whether this call cancelled the subscription:
     * false otherwise, so a repeated call changes nothing.
     */
    boolean cancel(UUID subscriptionId);

    /**
     * The current period ends while cancelling: CANCELLING → ENDED (design.md §5). Publishes {@link SubscriptionChanged}
     * of type ENDED, so the access feed removes the source. Returns whether this call ended the subscription: false
     * otherwise, so a repeated call changes nothing.
     */
    boolean periodEnded(UUID subscriptionId);

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
