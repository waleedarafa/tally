package com.tally.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.flags.domain.FlagCatalogue;
import com.tally.flags.domain.FlagChange;
import com.tally.flags.domain.Flags;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-049 (WAR-66): the flags catalogue saves and loads as a snapshot. The change log round-trips,
 * and every save replaces the stored catalogue in one transaction.
 */
class FlagPersistenceIT extends AbstractIntegrationTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final String NAME = "family-bundle";

    @Autowired
    FlagCatalogue catalogue;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM flags.flag");
    }

    @Test
    void anEmptyCatalogueLoadsWithNoNames() {
        assertThat(catalogue.load().names()).isEmpty();
    }

    @Test
    void aDefinedFlagRoundTrips() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        catalogue.save(flags);
        Flags loaded = catalogue.load();

        assertThat(loaded.isOn(NAME, ACCOUNT_A, "US")).isFalse();
        assertThat(loaded.changes(NAME)).containsExactly(new FlagChange(NOW, "defined, default off"));
    }

    @Test
    void anAccountAssignmentOverridesACountryAssignmentAfterReload() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.assignCountry(NAME, "AE", true, NOW);
        flags.assignAccount(NAME, ACCOUNT_A, false, NOW);

        catalogue.save(flags);
        Flags loaded = catalogue.load();

        assertThat(loaded.isOn(NAME, ACCOUNT_A, "AE")).isFalse();
        assertThat(loaded.isOn(NAME, ACCOUNT_B, "AE")).isTrue();
        assertThat(loaded.isOn(NAME, ACCOUNT_B, "US")).isFalse();
    }

    @Test
    void theChangeLogAndReleaseRoundTrip() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.setDefault(NAME, true, NOW.plusSeconds(1));
        flags.assignCountry(NAME, "AE", true, NOW.plusSeconds(2));
        flags.assignAccount(NAME, ACCOUNT_A, false, NOW.plusSeconds(3));
        flags.clearAccountAssignment(NAME, ACCOUNT_A, NOW.plusSeconds(4));
        flags.release(NAME, NOW.plusSeconds(5));

        catalogue.save(flags);
        Flags loaded = catalogue.load();

        assertThat(loaded.changes(NAME)).containsExactly(
                new FlagChange(NOW, "defined, default off"),
                new FlagChange(NOW.plusSeconds(1), "default set to on"),
                new FlagChange(NOW.plusSeconds(2), "country AE set to on"),
                new FlagChange(NOW.plusSeconds(3), "account 00000000-0000-0000-0000-00000000000a set to off"),
                new FlagChange(NOW.plusSeconds(4), "account 00000000-0000-0000-0000-00000000000a assignment cleared"),
                new FlagChange(NOW.plusSeconds(5), "fully released"));
        assertThat(loaded.defaultOn(NAME)).isTrue();
        Instant releasedAt = NOW.plusSeconds(5);
        assertThat(loaded.releasedAt(NAME)).isEqualTo(releasedAt);
        assertThat(loaded.isOverdue(NAME, releasedAt.plus(30, ChronoUnit.DAYS))).isFalse();
        assertThat(loaded.isOverdue(NAME, releasedAt.plus(30, ChronoUnit.DAYS).plusNanos(1))).isTrue();
        assertThat(loaded.isOn(NAME, ACCOUNT_A, "AE")).isTrue();
    }

    @Test
    void saveReplacesThePreviousCatalogue() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.define("zoo-flag", NOW);

        catalogue.save(flags);

        Flags onlyFamily = new Flags();
        onlyFamily.define(NAME, NOW);
        onlyFamily.setDefault(NAME, true, NOW.plusSeconds(1));

        catalogue.save(onlyFamily);

        Flags loaded = catalogue.load();

        assertThat(loaded.names()).containsExactly("family-bundle");
        assertThat(loaded.defaultOn(NAME)).isTrue();
        assertThatThrownBy(() -> loaded.isOn("zoo-flag", ACCOUNT_A, "US"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removingAFlagDeletesItsRows() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        flags.assignAccount(NAME, ACCOUNT_A, true, NOW);
        flags.assignCountry(NAME, "AE", true, NOW);

        catalogue.save(flags);

        flags.remove(NAME, NOW);

        catalogue.save(flags);

        assertThat(catalogue.load().names()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flags.flag", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flags.account_assignment", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flags.country_assignment", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flags.flag_change", Long.class)).isZero();
    }

    @Test
    void loadForUpdateOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> catalogue.loadForUpdate())
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void loadForUpdateReturnsWhatLoadReturns() {
        Flags flags = new Flags();
        flags.define(NAME, NOW);

        catalogue.save(flags);

        Flags loaded = tx.execute(status -> catalogue.loadForUpdate());

        assertThat(loaded.names()).containsExactly(NAME);
        assertThat(loaded.changes(NAME)).isEqualTo(catalogue.load().changes(NAME));
    }

    @Test
    void aSecondLoadForUpdateWaitsForTheFirstTransactionToEnd() throws Exception {
        Flags flags = new Flags();
        flags.define(NAME, NOW);
        catalogue.save(flags);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService service = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = service.submit(() -> tx.execute(status -> {
                catalogue.loadForUpdate();
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = service.submit(() -> tx.execute(status -> {
                catalogue.loadForUpdate();
                return true;
            }));

            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();

            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            service.shutdown();
        }
    }
}
