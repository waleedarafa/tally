package com.tally.subscription.application;

import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.SubscriptionChange;
import com.tally.subscription.domain.SubscriptionChanges;
import com.tally.subscription.domain.SubscriptionPeriod;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Records a change on the subscription's timeline and publishes it, in the caller's transaction:
 * stored in the event publication registry with the change (FR-022).
 */
@Component
class SubscriptionChangeLog {

    private final SubscriptionChanges changes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SubscriptionChangeLog(SubscriptionChanges changes, ApplicationEventPublisher events, Clock clock) {
        this.changes = changes;
        this.events = events;
        this.clock = clock;
    }

    void record(Subscription subscription, SubscriptionChangeType type) {
        Instant at = type == SubscriptionChangeType.STARTED ? subscription.startedAt() : clock.instant();
        SubscriptionPeriod period = subscription.currentPeriod();
        changes.add(new SubscriptionChange(subscription.id(), subscription.version(), type, subscription.planId(), at));
        events.publishEvent(new SubscriptionChanged(UUID.randomUUID(), at, subscription.accountId(),
                subscription.id(), subscription.planId(), subscription.version(), type, subscription.offerVersion(),
                period.number(), period.start(), period.end()));
    }
}
