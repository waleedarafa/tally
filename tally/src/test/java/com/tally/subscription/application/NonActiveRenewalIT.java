package com.tally.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import com.tally.support.Quiesce;
import com.tally.support.TestAccounts;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * D1: a renewal of a non-ACTIVE subscription ends or waits; it is never mistaken for an already-renewed
 * period. A cancelling subscription ends with the period that ends it; a trial subscription is not
 * renewed until its trial ends. Own context: it moves the clock.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=non-active-renewal"
})
class NonActiveRenewalIT extends AbstractIntegrationTest {

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    JdbcTemplate jdbc;

    String statusOf(UUID subscriptionId) {
        return jdbc.queryForObject("SELECT status FROM subscription.subscription WHERE id = ?", String.class,
                subscriptionId);
    }

    String endReasonOf(UUID subscriptionId) {
        return jdbc.queryForObject("SELECT end_reason FROM subscription.subscription WHERE id = ?", String.class,
                subscriptionId);
    }

    long periods(UUID subscriptionId) {
        return jdbc.queryForObject("SELECT count(*) FROM subscription.subscription_period WHERE subscription_id = ?",
                Long.class, subscriptionId);
    }

    /** The scheduled renewal item for the period after the current one, by payload. */
    String renewalStatus(UUID subscriptionId) {
        return jdbc.queryForObject("SELECT status FROM subscription.renewal WHERE payload = ?", String.class,
                subscriptionId + ",2");
    }

    Instant renewalDueAt(UUID subscriptionId) {
        return jdbc.queryForObject("SELECT due_at FROM subscription.renewal WHERE payload = ?",
                OffsetDateTime.class, subscriptionId + ",2").toInstant();
    }

    /** Moves the clock past the period end and lets the queue run the renewal for it. */
    void letTheRenewalRun(UUID account, UUID subscriptionId) {
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(15));
        clock.set(renewalDueAt(subscriptionId).plusSeconds(1));
    }

    /** Awaits the scheduled renewal row for the period after the current one. */
    void renewalRowWaits(UUID subscriptionId) {
        await().atMost(Duration.ofSeconds(15)).until(() ->
                "READY".equals(renewalStatus(subscriptionId)) && renewalDueAt(subscriptionId)
                        .isAfter(clock.instant().minusSeconds(5)));
    }

    @Test
    void aCancellingSubscriptionEndsWhenItsRenewalComesDue() {
        UUID account = testAccounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        SubscriptionView subscription = subscriptions.subscribe(account, "BASIC");
        assertThat(subscriptions.cancel(subscription.id())).as("cancel requested").isTrue();
        assertThat(statusOf(subscription.id())).isEqualTo("CANCELLING");

        letTheRenewalRun(account, subscription.id());

        await().atMost(Duration.ofSeconds(15)).until(() -> "DONE".equals(renewalStatus(subscription.id())));

        assertThat(statusOf(subscription.id())).isEqualTo("ENDED");
        assertThat(endReasonOf(subscription.id())).isEqualTo("CANCELLED");
        assertThat(periods(subscription.id())).as("no period 2").isEqualTo(1);
    }

    @Test
    void aTrialSubscriptionRenewsAfterTheTrialEnds() {
        UUID account = testAccounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        SubscriptionView subscription = subscriptions.startTrial(account, "BASIC");
        assertThat(statusOf(subscription.id())).isEqualTo("TRIAL");

        letTheRenewalRun(account, subscription.id());
        // The renewal is neither done nor renewed: the item returns to the queue with a later due time.
        renewalRowWaits(subscription.id());

        assertThat(statusOf(subscription.id())).as("still TRIAL").isEqualTo("TRIAL");
        assertThat(periods(subscription.id())).as("no period 2").isEqualTo(1);

        // The trial ends; once the recheck delay has passed, the renewal runs again.
        assertThat(subscriptions.endTrial(subscription.id())).isTrue();
        assertThat(statusOf(subscription.id())).isEqualTo("ACTIVE");
        clock.set(renewalDueAt(subscription.id()).plusSeconds(1));

        await().atMost(Duration.ofSeconds(15)).until(() -> "DONE".equals(renewalStatus(subscription.id())));

        assertThat(statusOf(subscription.id())).isEqualTo("ACTIVE");
        assertThat(periods(subscription.id())).as("period 2 exists").isEqualTo(2);
    }
}
