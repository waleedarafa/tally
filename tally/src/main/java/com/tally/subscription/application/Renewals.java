package com.tally.subscription.application;

import com.tally.account.AccountApi;
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
import com.tally.subscription.domain.Subscriptions;
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

    Renewals(DurableQueues queues, RenewalProperties properties, Subscriptions subscriptions,
            SubscriptionChangeLog changes, AccountApi accounts, TransactionTemplate transactions) {
        this.subscriptions = subscriptions;
        this.changes = changes;
        this.accounts = accounts;
        this.transactions = transactions;
        // Pollers start only once the application is up, so registering here is safe.
        this.queue = queues.register(DEFINITION.withWorkers(properties.renewalWorkers()), this);
    }

    /**
     * Schedules the renewal into the period after the current one, at local midnight of the day it
     * starts. Keyed by account and billing date, so it is scheduled once while the row exists.
     */
    void scheduleNext(Subscription subscription, ZoneId zone) {
        SubscriptionPeriod current = subscription.currentPeriod();
        queue.enqueue(subscription.accountId() + ":" + current.end(), BillingCalendar.billingTime(current.end(), zone),
                subscription.id() + "," + (current.number() + 1));
    }

    @Override
    public Outcome handle(QueueItem item) {
        String[] payload = item.payload().split(",");
        UUID subscriptionId = UUID.fromString(payload[0]);
        int period = Integer.parseInt(payload[1]);
        try {
            transactions.executeWithoutResult(tx -> renew(subscriptionId, period));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            // Another delivery of this item renewed the subscription first (version or period guard won).
            log.info("Period {} of subscription {} was already renewed", period, subscriptionId);
        }
        return Outcome.done();
    }

    private void renew(UUID subscriptionId, int period) {
        Subscription subscription = subscriptions.find(subscriptionId).orElseThrow();
        if (!subscription.renew(period)) {
            log.info("Period {} of subscription {} already exists", period, subscriptionId);
            return;
        }
        subscriptions.update(subscription);
        changes.record(subscription, SubscriptionChangeType.PERIOD_RENEWED);
        scheduleNext(subscription, accounts.get(subscription.accountId()).orElseThrow().timeZone());
    }
}
