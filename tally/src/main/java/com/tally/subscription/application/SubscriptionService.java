package com.tally.subscription.application;

import com.tally.catalog.BillingPeriod;
import com.tally.catalog.CatalogApi;
import com.tally.subscription.AlreadySubscribedException;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import com.tally.subscription.SubscriptionView;
import com.tally.subscription.TimelineEntry;
import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.SubscriptionChange;
import com.tally.subscription.domain.SubscriptionChangeRepository;
import com.tally.subscription.domain.SubscriptionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubscriptionService implements SubscriptionApi {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionChangeRepository changes;
    private final CatalogApi catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SubscriptionService(SubscriptionRepository subscriptions, SubscriptionChangeRepository changes,
            CatalogApi catalog, ApplicationEventPublisher events, Clock clock) {
        this.subscriptions = subscriptions;
        this.changes = changes;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubscriptionView subscribe(UUID accountId, String planId) {
        // Checks the account exists and the plan can be sold in its currency.
        catalog.quote(accountId, planId, BillingPeriod.MONTHLY);
        if (subscriptions.existsByAccountId(accountId)) {
            throw new AlreadySubscribedException(accountId);
        }
        Subscription subscription = Subscription.start(accountId, planId, clock.instant());
        try {
            subscriptions.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException e) {
            // A concurrent subscribe for the same account won: the unique constraint is the guard.
            throw new AlreadySubscribedException(accountId);
        }
        changes.save(new SubscriptionChange(subscription.id(), subscription.version(),
                SubscriptionChangeType.STARTED, planId, subscription.startedAt()));
        // Stored in the event publication registry in this transaction (ADR-022).
        events.publishEvent(new SubscriptionChanged(UUID.randomUUID(), subscription.startedAt(), accountId,
                subscription.id(), planId, subscription.version(), SubscriptionChangeType.STARTED));
        return new SubscriptionView(subscription.id(), accountId, planId, subscription.startedAt(),
                subscription.version());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID subscriptionId) {
        return changes.findBySubscriptionIdOrderByVersion(subscriptionId).stream()
                .map(c -> new TimelineEntry(c.version(), c.type(), c.planId(), c.occurredAt()))
                .toList();
    }
}
