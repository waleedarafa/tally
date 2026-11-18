package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.UnknownAccountException;
import com.tally.offers.UnknownPlanException;
import com.tally.support.TestAccounts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class SubscriptionApiIT extends AbstractIntegrationTest {

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    ApplicationEvents events;

    UUID account() {
        return testAccounts.open(Currency.getInstance("USD"), ZoneId.of("America/New_York")).id();
    }

    @Test
    void subscribingStartsTheSubscriptionAndPublishesAThinEvent() {
        UUID account = account();

        SubscriptionView started = subscriptions.subscribe(account, "STANDARD");

        assertThat(started.startedAt()).isEqualTo(clock.instant());
        assertThat(subscriptions.timeline(started.id()))
                .containsExactly(new TimelineEntry(1, SubscriptionChangeType.STARTED, "STANDARD", clock.instant()));
        assertThat(events.stream(SubscriptionChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.accountId()).isEqualTo(account);
            assertThat(e.subscriptionId()).isEqualTo(started.id());
            assertThat(e.type()).isEqualTo(SubscriptionChangeType.STARTED);
            assertThat(e.version()).isEqualTo(1);
            assertThat(e.occurredAt()).isEqualTo(clock.instant());
            assertThat(e.eventId()).isNotNull();
        });
    }

    @Test
    void anAccountHasAtMostOneSubscription() {
        UUID account = account();
        subscriptions.subscribe(account, "BASIC");

        assertThatThrownBy(() -> subscriptions.subscribe(account, "PREMIUM"))
                .isInstanceOf(AlreadySubscribedException.class);
        assertThat(events.stream(SubscriptionChanged.class)).hasSize(1);
    }

    @Test
    void refusesUnknownAccountsAndPlans() {
        assertThatThrownBy(() -> subscriptions.subscribe(UUID.randomUUID(), "BASIC"))
                .isInstanceOf(UnknownAccountException.class);
        assertThatThrownBy(() -> subscriptions.subscribe(account(), "GOLD"))
                .isInstanceOf(UnknownPlanException.class);
        assertThat(events.stream(SubscriptionChanged.class)).isEmpty();
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    String statusOf(UUID subscription) {
        return jdbc.queryForObject("SELECT status FROM subscription.subscription WHERE id = ?", String.class,
                subscription);
    }

    /** design.md §5 (proposed in ADR-038): end, with a reason, moves a live subscription to ENDED and publishes it. */
    @Test
    void endingTheAccountsLiveSubscriptionPublishesEnded() {
        UUID account = account();
        SubscriptionView started = subscriptions.subscribe(account, "BASIC");

        List<UUID> ended = subscriptions.endLiveSubscriptions(account, EndReason.WRITE_OFF);

        assertThat(ended).containsExactly(started.id());
        assertThat(statusOf(started.id())).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject("SELECT end_reason FROM subscription.subscription WHERE id = ?", String.class,
                started.id())).isEqualTo("WRITE_OFF");
        assertThat(subscriptions.timeline(started.id())).extracting(TimelineEntry::type)
                .containsExactly(SubscriptionChangeType.STARTED, SubscriptionChangeType.ENDED);
        assertThat(events.stream(SubscriptionChanged.class).filter(e -> e.type() == SubscriptionChangeType.ENDED))
                .singleElement().satisfies(e -> {
                    assertThat(e.accountId()).isEqualTo(account);
                    assertThat(e.subscriptionId()).isEqualTo(started.id());
                    assertThat(e.version()).as("a higher version than STARTED's").isEqualTo(2);
                    assertThat(e.periodNumber()).isEqualTo(1);
                });
        assertThat(subscriptions.endLiveSubscriptions(account, EndReason.WRITE_OFF)).as("ENDED refuses an end")
                .isEmpty();
        assertThat(subscriptions.endLiveSubscriptions(account(), EndReason.WRITE_OFF)).as("no subscription").isEmpty();
    }

    /**
     * Two "end" commands at the same time end it once (CLAUDE.md rule 7). The test holds the subscription's row lock
     * until both commands wait for it, so neither can finish first.
     */
    @Test
    void twoEndsAtTheSameTimeEndItOnce() throws Exception {
        UUID account = account();
        SubscriptionView started = subscriptions.subscribe(account, "BASIC");
        CompletableFuture<List<UUID>> first;
        CompletableFuture<List<UUID>> second;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM subscription.subscription WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, started.id());
                lock.executeQuery();
            }
            first = CompletableFuture.supplyAsync(() -> subscriptions.endLiveSubscriptions(account, EndReason.WRITE_OFF));
            second = CompletableFuture.supplyAsync(() -> subscriptions.endLiveSubscriptions(account, EndReason.WRITE_OFF));
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsWaitingOnALock() >= 2);
            holder.rollback();
        }

        List<UUID> ended = new ArrayList<>(first.get(15, TimeUnit.SECONDS));
        ended.addAll(second.get(15, TimeUnit.SECONDS));
        assertThat(ended).as("one of the two ended it; the other found it ended").containsExactly(started.id());
        assertThat(subscriptions.timeline(started.id())).extracting(TimelineEntry::type)
                .containsExactly(SubscriptionChangeType.STARTED, SubscriptionChangeType.ENDED);
        assertThat(jdbc.queryForObject("SELECT version FROM subscription.subscription WHERE id = ?", Integer.class,
                started.id())).isEqualTo(2);
    }

    /**
     * One live subscription per account: an account whose subscription ended signs up again, and the new subscription
     * is one renewal ahead. On the same day, both subscriptions' first periods end on the same date (D-26).
     */
    @Test
    void anAccountWithAnEndedSubscriptionSignsUpAgain() {
        UUID account = account();
        SubscriptionView first = subscriptions.subscribe(account, "BASIC");
        subscriptions.endLiveSubscriptions(account, EndReason.FIRST_PAYMENT_DECLINED);

        SubscriptionView again = subscriptions.subscribe(account, "STANDARD");

        assertThat(again.id()).isNotEqualTo(first.id());
        assertThat(statusOf(again.id())).isEqualTo("ACTIVE");
        assertThat(statusOf(first.id())).isEqualTo("ENDED");
        assertThat(jdbc.queryForList("SELECT payload FROM subscription.renewal WHERE status = 'READY'"
                + " AND split_part(queue_key, ':', 1) = ?", String.class, account.toString()))
                .as("each subscription's renewal row; the ended one's does nothing when it runs")
                .containsExactlyInAnyOrder(first.id() + ",2", again.id() + ",2");
        assertThatThrownBy(() -> subscriptions.subscribe(account, "PREMIUM"))
                .as("still one live subscription").isInstanceOf(AlreadySubscribedException.class);
    }
}
