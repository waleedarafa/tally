package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Q5: finished queue items and completed event publications older than {@code tally.retention.keep}
 * (30 days) are deleted; nothing unfinished is. The job runs on the platform's queue and reports
 * its health like every job (FR-017, FR-023).
 */
class RetentionIT extends AbstractIntegrationTest {

    static final String QUEUE = "payment.collection_queue";

    @Autowired
    Retention retention;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM " + QUEUE);
        jdbc.update("DELETE FROM event_publication");
    }

    @Test
    void deletesOnlyFinishedRowsOlderThanTheRetentionPeriod() {
        Instant now = clock.instant();
        Instant old = now.minus(Duration.ofDays(31));
        Instant recent = now.minus(Duration.ofDays(29));
        item("done-old", "DONE", old);
        item("done-recent", "DONE", recent);
        item("failed-old", "FAILED", old);
        item("ready-old", "READY", old);
        item("in-processing-old", "IN_PROCESSING", old);
        UUID completedOld = publication(old, old);
        UUID completedRecent = publication(recent, recent);
        UUID incompleteOld = publication(old, null);

        assertThat(retention.waitingWork()).isEqualTo(2);
        assertThat(meters.get("tally.job.waiting").tag("job", "retention").gauge().value()).isEqualTo(2);

        retention.run();

        assertThat(jdbc.queryForList("SELECT queue_key FROM " + QUEUE + " ORDER BY queue_key", String.class))
                .containsExactly("done-recent", "failed-old", "in-processing-old", "ready-old");
        assertThat(jdbc.queryForList("SELECT id FROM event_publication", UUID.class))
                .containsExactlyInAnyOrder(completedRecent, incompleteOld)
                .doesNotContain(completedOld);
        assertThat(retention.waitingWork()).isZero();
    }

    @Test
    void runsAsAPeriodicJobOnThePlatformQueue() {
        List<String> keys = jdbc.queryForList("SELECT queue_key FROM platform.jobs", String.class);
        assertThat(keys).contains("retention");
        assertThat(meters.find("tally.job.last_success_age_seconds").tag("job", "retention").gauge()).isNotNull();
        assertThat(meters.find("tally.job.overdue").tag("job", "retention").gauge()).isNotNull();
    }

    private void item(String key, String status, Instant claimedAt) {
        jdbc.update("INSERT INTO " + QUEUE + " (queue_key, due_at, payload, status, attempts, claimed_at, created_at)"
                        + " VALUES (?, ?, '', ?, 1, ?, ?)",
                key, claimedAt.atOffset(ZoneOffset.UTC), status, claimedAt.atOffset(ZoneOffset.UTC),
                claimedAt.atOffset(ZoneOffset.UTC));
    }

    private UUID publication(Instant published, Instant completed) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,"
                        + " completion_date, status, completion_attempts) VALUES (?, 'listener', 'Event', ?, ?, ?, ?, 1)",
                id, "{\"id\":\"" + id + "\"}", published.atOffset(ZoneOffset.UTC),
                completed == null ? null : completed.atOffset(ZoneOffset.UTC), completed == null ? "PUBLISHED" : "COMPLETED");
        return id;
    }
}
