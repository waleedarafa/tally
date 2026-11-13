package com.tally.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slice 2 PR-2 (ADR-033, design.md §4): {@link AccountStanding#set} publishes {@link StandingChanged} in its own
 * transaction, once, and only when the standing changes. The event carries the account's version after the change
 * as its sequence, and no cause text.
 *
 * <p>The recorder listens after commit, so it sees only what a committed transaction published.
 */
@Import(StandingChangedIT.Recorder.class)
@TestPropertySource(properties = "tally.test.context=standing-changed")
class StandingChangedIT extends AbstractIntegrationTest {

    static final Queue<StandingChanged> PUBLISHED = new ConcurrentLinkedQueue<>();

    @TestConfiguration
    static class Recorder {

        @TransactionalEventListener
        void on(StandingChanged event) {
            PUBLISHED.add(event);
        }
    }

    @Autowired
    AccountApi accounts;

    @Autowired
    AccountStanding standing;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    UUID newAccount() {
        return accounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai")).id();
    }

    List<StandingChanged> publishedFor(UUID account) {
        return PUBLISHED.stream().filter(e -> e.accountId().equals(account)).toList();
    }

    long version(UUID account) {
        return jdbc.queryForObject("SELECT version FROM account.account WHERE id = ?", Long.class, account);
    }

    @Test
    void aChangeOfStandingPublishesOneEventWithTheAccountsNewVersion() {
        UUID account = newAccount();

        standing.set(account, Standing.BLOCKED, "collections case 7");

        assertThat(publishedFor(account)).singleElement().satisfies(event -> {
            assertThat(event.standing()).isEqualTo(Standing.BLOCKED);
            assertThat(event.eventId()).isNotNull();
            assertThat(event.occurredAt()).isEqualTo(clock.instant());
            assertThat(event.sequence()).isEqualTo(version(account));
        });
    }

    @Test
    void settingTheSameStandingAgainPublishesNothing() {
        UUID account = newAccount();
        standing.set(account, Standing.BLOCKED, "collections case 7");
        long versionAfterTheChange = version(account);

        standing.set(account, Standing.BLOCKED, "collections case 7, again");

        assertThat(publishedFor(account)).hasSize(1);
        assertThat(version(account)).isEqualTo(versionAfterTheChange);
    }

    @Test
    void anAccountThatIsAlreadyGoodPublishesNothingWhenSetToGood() {
        UUID account = newAccount();

        standing.set(account, Standing.GOOD, "collections case closed");

        assertThat(publishedFor(account)).isEmpty();
    }

    @Test
    void eachChangeCarriesAHigherSequence() {
        UUID account = newAccount();

        standing.set(account, Standing.OVERDUE, "day 7");
        standing.set(account, Standing.BLOCKED, "day 10");
        standing.set(account, Standing.GOOD, "paid");

        assertThat(publishedFor(account)).extracting(StandingChanged::standing)
                .containsExactly(Standing.OVERDUE, Standing.BLOCKED, Standing.GOOD);
        assertThat(publishedFor(account)).extracting(StandingChanged::sequence).isSorted().doesNotHaveDuplicates();
        assertThat(publishedFor(account).getLast().sequence()).isEqualTo(version(account));
    }

    /**
     * Two callers set the same standing at the same time, both having read the account before either commits. The
     * version is an optimistic lock: the second waits for the first's row lock, then finds the version changed and
     * fails, so one event is published, not two (CLAUDE.md rule 7).
     */
    @Test
    void twoEqualChangesAtTheSameTimePublishOneEvent() throws Exception {
        UUID account = newAccount();
        CompletableFuture<Void> second = new CompletableFuture<>();

        transactions.executeWithoutResult(first -> {
            standing.set(account, Standing.BLOCKED, "case 7, first caller");
            // The first holds the row lock until it commits. The second reads the account as it was, GOOD at
            // version 0, then waits on that lock when it writes.
            CompletableFuture.runAsync(() -> standing.set(account, Standing.BLOCKED, "case 7, second caller"))
                    .whenComplete((ok, failure) -> {
                        if (failure != null) {
                            second.completeExceptionally(failure);
                        } else {
                            second.complete(ok);
                        }
                    });
            await().atMost(Duration.ofSeconds(10)).until(this::someoneWaitsForARowLock);
        });

        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(publishedFor(account)).singleElement().extracting(StandingChanged::standing)
                .isEqualTo(Standing.BLOCKED);
    }

    boolean someoneWaitsForARowLock() {
        // Sessions of this class's own database only (ADR-029: other test classes have other databases).
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class) > 0;
    }

    @Test
    void aChangeThatIsRolledBackPublishesNothing() {
        UUID account = newAccount();

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            standing.set(account, Standing.BLOCKED, "collections case 7");
            throw new IllegalStateException("the caller's transaction fails after the change");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(publishedFor(account)).isEmpty();
        assertThat(accounts.get(account).orElseThrow().standing()).isEqualTo(Standing.GOOD);
    }
}
