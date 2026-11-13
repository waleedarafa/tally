package com.tally.accessfeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.Standing;
import com.tally.account.StandingChanged;
import com.tally.shared.DomainEvent;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import com.tally.support.AbstractReconcilingTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slice 2 PR-3 (ADR-033, design.md §4): {@code accessfeed} keeps one row of current access facts per account. Its
 * sequence goes up by one only when the facts change. Source events can arrive twice, at the same time, or out of
 * order (CLAUDE.md rule 7); for each source the feed keeps the last source sequence it applied, and an event with no
 * sequence is applied ("order not known", FF-13).
 *
 * <p>Events are published in a transaction, as their modules publish them, and reach the feed through Spring
 * Modulith's registry; each test waits until the feed's listeners have finished with the account.
 */
@TestPropertySource(properties = "tally.test.context=access-feed")
class AccessFeedIT extends AbstractReconcilingTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    DataSource dataSource;

    void publish(DomainEvent event) {
        transactions.executeWithoutResult(tx -> events.publishEvent(event));
    }

    SubscriptionChanged subscription(UUID account, UUID subscription, int version, SubscriptionChangeType type) {
        return new SubscriptionChanged(UUID.randomUUID(), clock.instant(), account, subscription, "STANDARD",
                version, type);
    }

    StandingChanged standing(UUID account, Long sequence, Standing standing) {
        return new StandingChanged(UUID.randomUUID(), clock.instant(), account, sequence, standing);
    }

    /** Until no publication to the feed's listeners naming the account is still in flight; none may have failed. */
    void awaitFeed(UUID account) {
        String feed = "SELECT count(*) FROM event_publication WHERE listener_id LIKE 'com.tally.accessfeed.%'"
                + " AND serialized_event LIKE ? AND completion_date IS NULL";
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                feed + " AND status IS DISTINCT FROM 'FAILED'", Long.class, "%" + account + "%") == 0);
        assertThat(jdbc.queryForObject(feed + " AND status = 'FAILED'", Long.class, "%" + account + "%"))
                .as("failed feed publications").isZero();
    }

    long sequence(UUID account) {
        return jdbc.queryForObject("SELECT sequence FROM accessfeed.account_facts WHERE account_id = ?", Long.class,
                account);
    }

    String standingOf(UUID account) {
        return jdbc.queryForObject("SELECT standing FROM accessfeed.account_facts WHERE account_id = ?", String.class,
                account);
    }

    /** The sources, as "KIND id STATE sourceSequence". */
    List<String> sources(UUID account) {
        return jdbc.queryForList("SELECT kind || ' ' || source_id || ' ' || state || ' ' || coalesce(source_sequence::text,"
                + " '-') FROM accessfeed.fact_source WHERE account_id = ? ORDER BY source_id", String.class, account);
    }

    @Test
    void aSignUpMakesSequenceOneWithItsSubscriptionActive() {
        UUID account = subscribedAccount();
        awaitFeed(account);

        UUID subscription = jdbc.queryForObject("SELECT id FROM subscription.subscription WHERE account_id = ?",
                UUID.class, account);
        assertThat(sequence(account)).isEqualTo(1);
        assertThat(standingOf(account)).isEqualTo("GOOD");
        assertThat(sources(account)).containsExactly("SUBSCRIPTION " + subscription + " ACTIVE 1");
        assertThat(jdbc.queryForObject("SELECT produced_at FROM accessfeed.account_facts WHERE account_id = ?",
                OffsetDateTime.class, account).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void aRepeatedEventChangesNothing() {
        UUID account = UUID.randomUUID();
        SubscriptionChanged started = subscription(account, UUID.randomUUID(), 1, SubscriptionChangeType.STARTED);

        publish(started);
        awaitFeed(account);
        publish(started);
        awaitFeed(account);

        assertThat(sequence(account)).isEqualTo(1);
    }

    @Test
    void aRenewedPeriodChangesNothing() {
        UUID account = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        publish(subscription(account, subscription, 1, SubscriptionChangeType.STARTED));
        awaitFeed(account);

        publish(subscription(account, subscription, 2, SubscriptionChangeType.PERIOD_RENEWED));
        awaitFeed(account);

        assertThat(sequence(account)).isEqualTo(1);
        assertThat(sources(account)).containsExactly("SUBSCRIPTION " + subscription + " ACTIVE 2");
    }

    @Test
    void aStandingChangeAddsOne() {
        UUID account = UUID.randomUUID();
        publish(subscription(account, UUID.randomUUID(), 1, SubscriptionChangeType.STARTED));
        awaitFeed(account);

        publish(standing(account, 1L, Standing.BLOCKED));
        awaitFeed(account);

        assertThat(sequence(account)).isEqualTo(2);
        assertThat(standingOf(account)).isEqualTo("BLOCKED");
    }

    @Test
    void eventsOutOfOrderEndOnTheRightFacts() {
        UUID account = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();

        publish(standing(account, 3L, Standing.BLOCKED));
        awaitFeed(account);
        publish(standing(account, 2L, Standing.OVERDUE));
        awaitFeed(account);
        publish(subscription(account, subscription, 2, SubscriptionChangeType.PERIOD_RENEWED));
        awaitFeed(account);
        publish(subscription(account, subscription, 1, SubscriptionChangeType.STARTED));
        awaitFeed(account);

        assertThat(standingOf(account)).as("the older OVERDUE is ignored").isEqualTo("BLOCKED");
        assertThat(sources(account)).as("the older STARTED is ignored")
                .containsExactly("SUBSCRIPTION " + subscription + " ACTIVE 2");
        assertThat(sequence(account)).as("BLOCKED, then the subscription").isEqualTo(2);
    }

    @Test
    void anEventWithNoSequenceIsApplied() {
        UUID account = UUID.randomUUID();
        publish(standing(account, 5L, Standing.BLOCKED));
        awaitFeed(account);

        publish(standing(account, null, Standing.GOOD));
        awaitFeed(account);

        assertThat(standingOf(account)).as("order not known: applied, never treated as older").isEqualTo("GOOD");
        assertThat(sequence(account)).isEqualTo(2);
    }

    /**
     * Two copies of one event at the same time change the row once (CLAUDE.md rule 7). The test holds the
     * account's row lock until both copies wait for it, so neither can finish first.
     */
    @Test
    void twoCopiesOfOneEventAtTheSameTimeChangeTheRowOnce() throws Exception {
        UUID account = UUID.randomUUID();
        publish(subscription(account, UUID.randomUUID(), 1, SubscriptionChangeType.STARTED));
        awaitFeed(account);
        SubscriptionChanged second = subscription(account, UUID.randomUUID(), 1, SubscriptionChangeType.STARTED);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT account_id FROM accessfeed.account_facts WHERE account_id = ? FOR UPDATE")) {
                lock.setObject(1, account);
                lock.executeQuery();
            }
            publish(second);
            publish(second);
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsWaitingOnALock() >= 2);
            holder.rollback();
        }
        awaitFeed(account);

        assertThat(sequence(account)).isEqualTo(2);
        assertThat(sources(account)).hasSize(2);
    }

    /**
     * Two different events of one account at the same time: both facts survive, and the sequence goes up by two.
     * Without the row lock, both would read sequence 1 and write sequence 2, and one fact would be lost.
     */
    @Test
    void twoDifferentEventsAtTheSameTimeBothCount() throws Exception {
        UUID account = UUID.randomUUID();
        publish(subscription(account, UUID.randomUUID(), 1, SubscriptionChangeType.STARTED));
        awaitFeed(account);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT account_id FROM accessfeed.account_facts WHERE account_id = ? FOR UPDATE")) {
                lock.setObject(1, account);
                lock.executeQuery();
            }
            publish(standing(account, 1L, Standing.BLOCKED));
            publish(subscription(account, UUID.randomUUID(), 1, SubscriptionChangeType.STARTED));
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsWaitingOnALock() >= 2);
            holder.rollback();
        }
        awaitFeed(account);

        assertThat(sequence(account)).isEqualTo(3);
        assertThat(standingOf(account)).isEqualTo("BLOCKED");
        assertThat(sources(account)).hasSize(2);
    }

    /** Sessions of this test class's database that wait on a lock (ADR-029: other classes have other databases). */
    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    @Test
    void theFactsOfOneAccountAreItsOwn() {
        UUID one = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        publish(standing(one, 1L, Standing.BLOCKED));
        publish(subscription(other, UUID.randomUUID(), 1, SubscriptionChangeType.STARTED));
        awaitFeed(one);
        awaitFeed(other);

        assertThat(Map.of("one", standingOf(one), "other", standingOf(other)))
                .containsEntry("one", "BLOCKED").containsEntry("other", "GOOD");
        assertThat(sources(one)).isEmpty();
    }
}
