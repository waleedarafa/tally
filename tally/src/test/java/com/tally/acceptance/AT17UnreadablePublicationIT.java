package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.PeriodicJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import spike.modulith.SpikeConfiguration;
import spike.modulith.SpikeEvent;
import spike.modulith.SpikeListener;
import spike.modulith.SpikeListener.Behaviour;

/**
 * AT-17. One unreadable publication among others (ADR-016, spike S-1). Pass when: the others are still
 * resubmitted and handled, and the unreadable ones stay incomplete and visible in job health.
 *
 * <p>Two publications are damaged, one each way S-1 found: a payload that no longer parses, which the
 * resubmission predicate reads and skips (S1d), and an event class that is gone, which the registry drops
 * before the predicate sees it (S1f). Both are published first, so they are read first. The test runs
 * the resubmission job itself: its row is shared by every test context, each on its own clock.
 */
@Import(SpikeConfiguration.class)
@TestPropertySource(properties = "tally.test.context=at17")
class AT17UnreadablePublicationIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    List<PeriodicJob> jobs;

    final List<UUID> damaged = new ArrayList<>();

    /** An unreadable row left behind would stay incomplete for every later test on the shared database. */
    @AfterEach
    void removeDamaged() {
        damaged.forEach(row -> jdbc.update("DELETE FROM event_publication WHERE id = ?", row));
    }

    UUID failedPublication() {
        UUID id = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(id, Behaviour.FAIL);
        transactions.executeWithoutResult(tx -> events.publishEvent(new SpikeEvent(id)));
        clock.advance(Duration.ofSeconds(1));
        return id;
    }

    UUID row(UUID eventId) {
        return jdbc.queryForObject("SELECT id FROM event_publication WHERE serialized_event LIKE ?", UUID.class,
                "%" + eventId + "%");
    }

    String status(UUID row) {
        return jdbc.queryForObject("SELECT status FROM event_publication WHERE id = ?", String.class, row);
    }

    UUID damage(UUID eventId, String sql) {
        UUID row = row(eventId);
        jdbc.update(sql, row);
        damaged.add(row);
        return row;
    }

    @Test
    void oneUnreadablePublicationAmongOthersNeverBlocksTheRest() throws Exception {
        UUID badPayload = failedPublication();
        UUID missingClass = failedPublication();
        UUID first = failedPublication();
        UUID second = failedPublication();
        for (UUID id : List.of(badPayload, missingClass, first, second)) {
            await().atMost(Duration.ofSeconds(10)).until(() -> "FAILED".equals(status(row(id))));
            SpikeListener.BEHAVIOUR.put(id, Behaviour.SUCCEED);
        }
        UUID badPayloadRow = damage(badPayload,
                "UPDATE event_publication SET serialized_event = '{\"id\":\"not-a-uuid\"}' WHERE id = ?");
        UUID missingClassRow = damage(missingClass,
                "UPDATE event_publication SET event_type = 'spike.modulith.RemovedEvent' WHERE id = ?");
        clock.advance(Duration.ofMinutes(10));

        jobs.stream().filter(job -> job.name().equals("event-resubmission")).findFirst().orElseThrow().run();

        // The others are resubmitted and handled.
        await().atMost(Duration.ofSeconds(10)).until(() ->
                "COMPLETED".equals(status(row(first))) && "COMPLETED".equals(status(row(second))));
        assertThat(SpikeListener.invocations(first)).isEqualTo(2);
        assertThat(SpikeListener.invocations(second)).isEqualTo(2);
        // The unreadable ones are never resubmitted, only reported: repairing them needs a person.
        assertThat(status(badPayloadRow)).isEqualTo("FAILED");
        assertThat(status(missingClassRow)).isEqualTo("FAILED");
        assertThat(meters.get("tally.events.unreadable").gauge().value()).as("skipped by the predicate")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"
                + " AND id IN (?, ?)", Long.class, badPayloadRow, missingClassRow))
                .as("both counted by tally.events.incomplete").isEqualTo(2);
    }
}
