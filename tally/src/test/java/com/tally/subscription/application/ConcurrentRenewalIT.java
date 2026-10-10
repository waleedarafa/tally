package com.tally.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.platform.QueueItem;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionView;
import com.tally.subscription.TimelineEntry;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-003: two deliveries of the same renewal at the same moment, as after a reaper re-queues an item
 * still running elsewhere, renew once. The version column (optimistic lock) and the period's unique
 * constraint decide; the loser is absorbed.
 */
class ConcurrentRenewalIT extends AbstractIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    Renewals renewals;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void twoRenewalsOfOnePeriodAtOnceRenewOnce() {
        for (int round = 0; round < 10; round++) {
            UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
            SubscriptionView subscription = subscriptions.subscribe(account, "BASIC");
            QueueItem item = new QueueItem(round, account + ":renewal", clock.instant(), subscription.id() + ",2", 1);
            CyclicBarrier together = new CyclicBarrier(2);

            List<CompletableFuture<Void>> both = List.of(1, 2).stream().map(i -> CompletableFuture.runAsync(() -> {
                await(together);
                renewals.handle(item);
            })).toList();
            CompletableFuture.allOf(both.toArray(CompletableFuture[]::new)).join();

            List<TimelineEntry> timeline = subscriptions.timeline(subscription.id());
            assertThat(timeline).extracting(TimelineEntry::type)
                    .containsExactly(SubscriptionChangeType.STARTED, SubscriptionChangeType.PERIOD_RENEWED);
            assertThat(timeline).extracting(TimelineEntry::version).containsExactly(1, 2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.subscription_period"
                    + " WHERE subscription_id = ?", Long.class, subscription.id())).isEqualTo(2);
        }
    }

    static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
