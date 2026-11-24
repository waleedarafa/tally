package com.tally.subscription.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.Standing;
import com.tally.account.UnknownAccountException;
import com.tally.offers.Offers;
import com.tally.offers.UnknownPlanException;
import com.tally.subscription.AccountBlockedException;
import com.tally.subscription.AlreadySubscribedException;
import com.tally.subscription.EndReason;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionView;
import com.tally.subscription.TimelineEntry;
import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.SubscriptionChanges;
import com.tally.subscription.domain.Subscriptions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubscriptionService implements SubscriptionApi {

    private final Subscriptions subscriptions;
    private final SubscriptionChanges changes;
    private final SubscriptionChangeLog changeLog;
    private final Renewals renewals;
    private final Offers offers;
    private final AccountApi accounts;
    private final Clock clock;

    SubscriptionService(Subscriptions subscriptions, SubscriptionChanges changes, SubscriptionChangeLog changeLog,
            Renewals renewals, Offers offers, AccountApi accounts, Clock clock) {
        this.subscriptions = subscriptions;
        this.changes = changes;
        this.changeLog = changeLog;
        this.renewals = renewals;
        this.offers = offers;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Starts the subscription on the offer's current version, which it keeps (decision A), with period 1
     * from the local day it starts; publishes STARTED and schedules the renewal into period 2.
     */
    @Override
    @Transactional
    public SubscriptionView subscribe(UUID accountId, String planId) {
        return start(accountId, planId, false);
    }

    /**
     * Starts a subscription in TRIAL (design.md §5): same guards and the same period 1 as {@link #subscribe},
     * only the status differs. Publishes STARTED like any start, and schedules the renewal into period 2.
     */
    @Override
    @Transactional
    public SubscriptionView startTrial(UUID accountId, String planId) {
        return start(accountId, planId, true);
    }

    private SubscriptionView start(UUID accountId, String planId, boolean trial) {
        AccountView account = accounts.get(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
        if (account.standing() == Standing.BLOCKED) {
            throw new AccountBlockedException(accountId);
        }
        // The offer can be sold in the account's currency, at its current version.
        int offerVersion = offers.currentVersion(planId, accountId)
                .orElseThrow(() -> new UnknownPlanException(planId, accountId));
        if (subscriptions.hasLive(accountId)) {
            throw new AlreadySubscribedException(accountId);
        }
        Subscription subscription = trial
                ? Subscription.startTrial(accountId, planId, offerVersion, clock.instant(), account.timeZone())
                : Subscription.start(accountId, planId, offerVersion, clock.instant(), account.timeZone());
        if (!subscriptions.addIfNoneLive(subscription)) {
            // A concurrent subscribe for the same account won: one live subscription per account is the guard.
            throw new AlreadySubscribedException(accountId);
        }
        changeLog.record(subscription, SubscriptionChangeType.STARTED);
        renewals.scheduleNext(subscription, account.timeZone());
        return new SubscriptionView(subscription.id(), accountId, planId, subscription.startedAt(),
                subscription.version(), subscription.offerVersion());
    }

    /**
     * Trial conversion: TRIAL → ACTIVE (design.md §5). Returns true only if this call converted the subscription;
     * false if it is not in TRIAL, so a repeated call changes nothing. No event is published: there is no change type
     * for the trial ending (design.md §16).
     */
    @Override
    @Transactional
    public boolean endTrial(UUID subscriptionId) {
        return subscriptions.lock(subscriptionId).filter(s -> s.endTrial()).map(ended -> {
            subscriptions.update(ended);
            return true;
        }).orElse(false);
    }

    /**
     * A cancel requested: TRIAL or ACTIVE → CANCELLING (design.md §5); access ends when the current period ends.
     * Publishes CANCELLED. Returns true only if this call cancelled the subscription.
     */
    @Override
    @Transactional
    public boolean cancel(UUID subscriptionId) {
        return subscriptions.lock(subscriptionId).filter(s -> s.cancel()).map(cancelled -> {
            subscriptions.update(cancelled);
            changeLog.record(cancelled, SubscriptionChangeType.CANCELLED);
            return true;
        }).orElse(false);
    }

    /**
     * The current period ends while cancelling: CANCELLING → ENDED (design.md §5). Publishes ENDED, so the access
     * feed removes the source. Returns true only if this call ended the subscription.
     */
    @Override
    @Transactional
    public boolean periodEnded(UUID subscriptionId) {
        return subscriptions.lock(subscriptionId).filter(s -> s.periodEnded(clock.instant())).map(ended -> {
            subscriptions.update(ended);
            changeLog.record(ended, SubscriptionChangeType.ENDED);
            return true;
        }).orElse(false);
    }

    /** Under each live subscription's row lock: of two ends at the same time, the second finds none live. */
    @Override
    @Transactional
    public List<UUID> endLiveSubscriptions(UUID accountId, EndReason reason) {
        List<UUID> ended = new ArrayList<>();
        for (Subscription subscription : subscriptions.lockLive(accountId)) {
            if (subscription.end(reason, clock.instant())) {
                subscriptions.update(subscription);
                changeLog.record(subscription, SubscriptionChangeType.ENDED);
                ended.add(subscription.id());
            }
        }
        return ended;
    }

    /** Under the subscription's row lock: of two ends at the same time, the second finds it ended. */
    @Override
    @Transactional
    public boolean end(UUID subscriptionId, EndReason reason) {
        return subscriptions.lock(subscriptionId).filter(s -> s.end(reason, clock.instant())).map(ended -> {
            subscriptions.update(ended);
            changeLog.record(ended, SubscriptionChangeType.ENDED);
            return true;
        }).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID subscriptionId) {
        return changes.historyOf(subscriptionId).stream()
                .map(c -> new TimelineEntry(c.version(), c.type(), c.planId(), c.occurredAt()))
                .toList();
    }
}
