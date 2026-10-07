package com.tally.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.payment.PaymentSucceeded;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Brief section 3: access before the first payment is a setting, while ADR-019 is Proposed. */
class AccessIT {

    abstract static class Base extends AbstractIntegrationTest {

        @Autowired
        EntitlementApi entitlements;

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

        PaymentSucceeded paid(UUID account) {
            return new PaymentSucceeded(UUID.randomUUID(), clock.instant(), account, "INV-x", Money.of(999, "USD"),
                    "ch_x");
        }
    }

    @Nested
    class GrantBeforePayment extends Base {

        @Test
        void accessStartsWithTheSubscription() {
            UUID account = UUID.randomUUID();
            assertThat(entitlements.hasAccess(account)).isFalse();

            publish(started(account));
            await().atMost(Duration.ofSeconds(5)).until(() -> entitlements.hasAccess(account));

            // A later payment, or a redelivered event, changes nothing.
            publish(paid(account));
            publish(started(account));
            await().during(Duration.ofMillis(500)).until(() -> entitlements.hasAccess(account));
        }
    }

    @Nested
    @TestPropertySource(properties = "tally.access.grant-before-payment=false")
    class GrantOnFirstPayment extends Base {

        @Test
        void accessWaitsForTheFirstSuccessfulPayment() {
            UUID account = UUID.randomUUID();

            publish(started(account));
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                    .until(() -> !entitlements.hasAccess(account));

            publish(paid(account));
            await().atMost(Duration.ofSeconds(5)).until(() -> entitlements.hasAccess(account));
        }
    }
}
