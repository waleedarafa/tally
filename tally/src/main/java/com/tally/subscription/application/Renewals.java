package com.tally.subscription.application;

import com.tally.account.AccountApi;
import com.tally.account.Standing;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.domain.BillingCalendar;
import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.SubscriptionPeriod;
import com.tally.subscription.domain.SubscriptionStatus;
import com.tally.subscription.domain.Subscriptions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The billing run (ADR-003, FR-017), on Subscription's own queue: one item per subscription and
 * period, due at local midnight of the period's first day. No leader: any instance claims due items.
 * Each renewal moves the subscription into its next period, publishes PERIOD_RENEWED and schedules the
 * one after, in one transaction. {@code renew(n)} makes a repeat harmless, and the version column makes
 * two renewals at the same time end with one.
 */
@Component
@EnableConfigurationProperties(RenewalProperties.class)
class Renewals implements QueueHandler {

    /** What a renewal did, so a non-ACTIVE state is not mistaken for an existing period (review of #95, finding 6). */
    private enum Result { RENEWED, ALREADY_RENEWED, ENDED, CANCELLING_ENDED, IN_TRIAL }

    private static final Logger log = LoggerFactory.getLogger(Renewals.class);

    // The permanent guard (FR-037): one period per subscription and number. Re-running a renewal whose
    // queue row retention deleted finds the period and renews nothing (D1).
    static final QueueDefinition DEFINITION = QueueDefinition.of("renewals", "subscription.renewal",
            "subscription.subscription_period_one_per_number").asMoneyQueue();

    private final DurableQueue queue;
    private final Subscriptions subscriptions;
    private final SubscriptionChangeLog changes;
    private final AccountApi accounts;
    private final TransactionTemplate transactions;
    private final Duration blockedRecheck;
    private final Clock clock;

    Renewals(DurableQueues queues, RenewalProperties properties, Subscriptions subscriptions,
            SubscriptionChangeLog changes, AccountApi accounts, TransactionTemplate transactions, Clock clock) {
        this.subscriptions = subscriptions;
        this.changes = changes;
        this.accounts = accounts;
        this.transactions = transactions;
        this.blockedRecheck = properties.blockedRecheck();
        this.clock = clock;
        // Pollers start only once the application is up, so registering here is safe.
        this.queue = queues.register(DEFINITION.withWorkers(properties.renewalWorkers()), this);
    }

    /**
     * Schedules the renewal into the period after the current one, at local midnight of the day it
     * starts. Keyed by account and billing date, so it is scheduled once while the row exists. The payload,
     * {@code <subscription id>,<period number>}, is also read by the invariant check
     * {@code subscription.next-renewal-scheduled}; change both together.
     */
    void scheduleNext(Subscription subscription, ZoneId zone) {
        SubscriptionPeriod current = subscription.currentPeriod();
        // Keyed by subscription too (D-26): an account whose subscription ended may sign up again on the same billing
        // day, and the ended one's renewal row still waits. The account comes first, so a key still names it.
        queue.enqueue(subscription.accountId() + ":" + subscription.id() + ":" + current.end(), BillingCalendar.billingTime(current.end(), zone),
                subscription.id() + "," + (current.number() + 1));
    }

    @Override
    public Outcome handle(QueueItem item) {
        String[] payload = item.payload().split(",");
        UUID subscriptionId = UUID.fromString(payload[0]);
        int period = Integer.parseInt(payload[1]);
        if (blocked(subscriptionId)) {
            // Decision 3 (I6): no new period and no new invoice while the account is BLOCKED. The item comes back
            // and looks again; the period keeps its dates. Not a failure: the renewal row stays scheduled.
            log.info("The account of subscription {} is BLOCKED: period {} waits", subscriptionId, period);
            return Outcome.retryAt(clock.instant().plus(blockedRecheck));
        }
        Result result;
        try {
            result = transactions.execute(tx -> renew(subscriptionId, period));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            // A conflict is a duplicate only if the fact the guard protects holds: period n exists, so
            // another delivery renewed first. Any other failure rolled the renewal back: rethrow, so the
            // item is retried and shows in the queue's health instead of being lost (D1, CLAUDE.md rule 11).
            if (!periodExists(subscriptionId, period)) {
                throw e;
            }
            log.info("Period {} of subscription {} was already renewed", period, subscriptionId);
            result = Result.ALREADY_RENEWED;
        }
        if (result == Result.IN_TRIAL) {
            // A trial subscription is not renewed (design.md §5): the item comes back and looks again
            // once the trial has ended.
            log.info("Subscription {} is in TRIAL: period {} waits until the trial ends", subscriptionId, period);
            return Outcome.retryAt(clock.instant().plus(blockedRecheck));
        }
        return Outcome.done();
    }

    /**
     * Whether the subscription's account is BLOCKED now. A policy, not an invariant (design.md §2, I6): a standing set
     * just after this read lets one renewal through, and its invoice joins the case.
     */
    private boolean blocked(UUID subscriptionId) {
        return Boolean.TRUE.equals(transactions.execute(tx -> subscriptions.find(subscriptionId)
                .filter(s -> s.status() == SubscriptionStatus.ACTIVE)
                .flatMap(s -> accounts.get(s.accountId()))
                .map(account -> account.standing() == Standing.BLOCKED).orElse(false)));
    }

    /** In a fresh transaction, after the failed one rolled back. */
    private boolean periodExists(UUID subscriptionId, int period) {
        return Boolean.TRUE.equals(transactions.execute(tx -> subscriptions.find(subscriptionId)
                .map(s -> s.periods().stream().anyMatch(p -> p.number() == period)).orElse(false)));
    }

    private Result renew(UUID subscriptionId, int period) {
        Subscription subscription = subscriptions.find(subscriptionId).orElseThrow();
        if (subscription.status() == SubscriptionStatus.ENDED) {
            log.info("Subscription {} has ended; period {} is not renewed", subscriptionId, period);
            return Result.ENDED;
        }
        if (subscription.status() == SubscriptionStatus.CANCELLING) {
            // design.md §5: a cancelling subscription ends when its period ends, and is not renewed.
            if (subscription.periodEnded(clock.instant())) {
                subscriptions.update(subscription);
                changes.record(subscription, SubscriptionChangeType.ENDED);
                log.info("Subscription {} was cancelling: it ends with period {}", subscriptionId, period);
            }
            return Result.CANCELLING_ENDED;
        }
        if (subscription.status() == SubscriptionStatus.TRIAL) {
            // design.md §5: a trial subscription is not renewed; the renewal waits until the trial ends.
            return Result.IN_TRIAL;
        }
        if (!subscription.renew(period)) {
            log.info("Period {} of subscription {} already exists", period, subscriptionId);
            return Result.ALREADY_RENEWED;
        }
        subscriptions.update(subscription);
        changes.record(subscription, SubscriptionChangeType.PERIOD_RENEWED);
        scheduleNext(subscription, accounts.get(subscription.accountId()).orElseThrow().timeZone());
        return Result.RENEWED;
    }
}
