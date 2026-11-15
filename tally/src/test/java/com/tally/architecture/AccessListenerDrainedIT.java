package com.tally.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.support.PerContextDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * ADR-028 (Slice 2 PR-9): the in-process access module's listener is removed only when it has no incomplete
 * publication. The check runs where it cannot be skipped: migration V35, before V36 drops the module's schema, on
 * every database Tally migrates. It stops the deploy when a publication of the listener is incomplete, so nothing is
 * dropped and the previous release, which still has the listener, completes it.
 *
 * <p>This test runs V35's statement against a database of its own, with and without such a publication.
 */
class AccessListenerDrainedIT {

    static final String LISTENER = "com.tally.access.application.AccessService.on("
            + "com.tally.subscription.SubscriptionChanged)";
    static final Path CHECK = Path.of("src/main/resources/db/migration/V35__access_listener_drained.sql");

    static JdbcTemplate database() {
        AbstractIntegrationTest.POSTGRES.start();
        return new JdbcTemplate(new DriverManagerDataSource(PerContextDatabase.newDatabase("access_drained"),
                AbstractIntegrationTest.POSTGRES.getUsername(), AbstractIntegrationTest.POSTGRES.getPassword()));
    }

    static void publication(JdbcTemplate jdbc, String listener, boolean completed) {
        Instant now = Instant.parse("2026-11-15T06:00:00Z");
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,"
                        + " completion_date, status, completion_attempts) VALUES (?, ?, ?, '{}', ?, ?, ?, 1)",
                UUID.randomUUID(), listener, "com.tally.subscription.SubscriptionChanged", now.atOffset(ZoneOffset.UTC),
                completed ? now.atOffset(ZoneOffset.UTC) : null, completed ? "COMPLETED" : "PUBLISHED");
    }

    static String check() throws IOException {
        return Files.readString(CHECK);
    }

    @Test
    void anIncompletePublicationOfTheListenerStopsTheMigration() throws IOException {
        JdbcTemplate jdbc = database();
        publication(jdbc, LISTENER, false);

        assertThatThrownBy(() -> jdbc.execute(check()))
                .hasMessageContaining("AccessService.on")
                .hasMessageContaining("1 incomplete publication");
    }

    @Test
    void completedPublicationsOfTheListenerAndOthersIncompleteLetItPass() throws IOException {
        JdbcTemplate jdbc = database();
        publication(jdbc, LISTENER, true);
        publication(jdbc, "com.tally.accessfeed.application.AccessFeedListeners.on("
                + "com.tally.subscription.SubscriptionChanged)", false);

        jdbc.execute(check());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication", Long.class)).isEqualTo(2);
    }
}
