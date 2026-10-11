package com.tally.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/** H7: a subscription gives access from sign-up, without waiting for a payment (Slice 1R PR-10). */
class AccessIT extends AbstractIntegrationTest {

    @Autowired
    AccessApi access;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    void publish(Object event) {
        transactions.executeWithoutResult(tx -> events.publishEvent(event));
    }

    SubscriptionChanged started(UUID account) {
        return new SubscriptionChanged(UUID.randomUUID(), clock.instant(), account, UUID.randomUUID(), "BASIC", 1,
                SubscriptionChangeType.STARTED);
    }

    @Test
    void accessStartsWithTheSubscription() {
        UUID account = UUID.randomUUID();
        assertThat(access.hasAccess(account)).isFalse();

        publish(started(account));
        await().atMost(Duration.ofSeconds(5)).until(() -> access.hasAccess(account));

        // A redelivered event changes nothing.
        publish(started(account));
        await().during(Duration.ofMillis(500)).until(() -> access.hasAccess(account));
    }
}
