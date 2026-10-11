package com.tally.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link Quiesce} must see work that is in flight as events. Between a renewal finishing and its payment
 * attempt being written, the work exists only as event publications (SubscriptionChanged, then InvoiceIssued).
 * A wait that missed them let AT-3 count charges before the newest invoice existed (run 38149632762: expected
 * 1, was 0; run 38150282173).
 */
class QuiesceIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anEventStillInFlightForTheAccountIsWorkInProgress() {
        UUID account = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        // A publication no listener has completed yet. Its type does not exist, so resubmission never runs it.
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,"
                        + " status, completion_attempts) VALUES (?, 'QuiesceIT.inFlight', 'com.tally.support.NoSuchEvent',"
                        + " ?, ?, 'PROCESSING', 1)",
                publication, "{\"accountId\":\"" + account + "\"}", clock.instant().atOffset(ZoneOffset.UTC));
        try {
            assertThatThrownBy(() -> Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(1)))
                    .as("an incomplete publication naming the account is work in progress")
                    .isInstanceOf(ConditionTimeoutException.class);

            jdbc.update("UPDATE event_publication SET completion_date = ?, status = 'COMPLETED' WHERE id = ?",
                    clock.instant().atOffset(ZoneOffset.UTC), publication);
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(5));
        } finally {
            jdbc.update("DELETE FROM event_publication WHERE id = ?", publication);
        }
    }

    /** A FAILED publication waits for resubmission: nothing is running, so it does not hold a time jump. */
    @Test
    void aFailedPublicationIsNotWorkInProgress() {
        UUID account = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,"
                        + " status, completion_attempts) VALUES (?, 'QuiesceIT.failed', 'com.tally.support.NoSuchEvent',"
                        + " ?, ?, 'FAILED', 1)",
                publication, "{\"accountId\":\"" + account + "\"}", clock.instant().atOffset(ZoneOffset.UTC));
        try {
            Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(5));
        } finally {
            jdbc.update("DELETE FROM event_publication WHERE id = ?", publication);
        }
    }
}
