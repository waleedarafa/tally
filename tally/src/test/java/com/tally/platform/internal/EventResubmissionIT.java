package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** FR-022: incomplete publications are resubmitted by a queue job, and monitored. */
@Import(SpikeConfiguration.class)
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=event-resubmission"
})
class EventResubmissionIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    /**
     * Starts from no unfinished publications, and lets the job's first run, as the context starts, end before time
     * moves: its next run is set from the clock when that run ends (ADR-029).
     */
    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM event_publication WHERE completion_date IS NULL");
        await().atMost(Duration.ofSeconds(10)).until(() -> !jobLastSuccess().equals(Instant.EPOCH));
    }

    double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    Instant jobLastSuccess() {
        OffsetDateTime at = jdbc.queryForObject(
                "SELECT last_success_at FROM platform.jobs WHERE queue_key = 'event-resubmission'", OffsetDateTime.class);
        return at == null ? Instant.EPOCH : at.toInstant();
    }

    /** Moves the clock and waits until the resubmission job has run at the new time. */
    void advanceAndRunJob(Duration by) {
        clock.advance(by);
        Instant now = clock.instant();
        await().atMost(Duration.ofSeconds(10)).until(() -> !jobLastSuccess().isBefore(now));
    }

    @Test
    void aFailedListenerIsResubmittedOnceOlderThanTheThresholdAndTheGaugesFollow() {
        UUID id = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(id, Behaviour.FAIL);
        transactions.executeWithoutResult(tx -> events.publishEvent(new SpikeEvent(id)));
        await().atMost(Duration.ofSeconds(10)).until(() -> gauge("tally.events.incomplete") == 1);
        SpikeListener.BEHAVIOUR.put(id, Behaviour.SUCCEED);

        // Younger than 5 minutes: the job runs but leaves it alone.
        advanceAndRunJob(Duration.ofMinutes(2));
        assertThat(SpikeListener.invocations(id)).isEqualTo(1);
        assertThat(gauge("tally.events.incomplete")).isEqualTo(1);
        assertThat(gauge("tally.events.oldest_incomplete_age_seconds")).isEqualTo(120);

        // Older than 5 minutes: resubmitted once, and complete.
        advanceAndRunJob(Duration.ofMinutes(4));
        await().atMost(Duration.ofSeconds(10)).until(() -> gauge("tally.events.incomplete") == 0);
        assertThat(SpikeListener.invocations(id)).isEqualTo(2);
        assertThat(gauge("tally.events.oldest_incomplete_age_seconds")).isZero();

        // Later runs do not run it again.
        advanceAndRunJob(Duration.ofMinutes(10));
        assertThat(SpikeListener.invocations(id)).isEqualTo(2);
    }
}
