package com.tally.platform.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Health of the event publication registry (FR-022, brief section 4), read when scraped:
 * <ul>
 *   <li>{@code tally.events.incomplete}: publications whose listener has not completed</li>
 *   <li>{@code tally.events.oldest_incomplete_age_seconds}: age of the oldest of them; FR-022 alerts
 *       above 15 minutes</li>
 * </ul>
 * {@link EventResubmission} adds {@code tally.events.unreadable}: publications its last run skipped
 * because their event could not be read (ADR-016, AT-17).
 * The registry dates publications with the injected clock (Spike A), so ages use it too.
 */
@Component
class EventHealth {

    /** Linked from the description of every event gauge an alert reads. */
    static final String RUNBOOK = "docs/runbooks/unreadable-event-publication.md";

    EventHealth(JdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
        Gauge.builder("tally.events.incomplete", jdbc, EventHealth::incomplete)
                .description("Publications whose listener has not completed, readable or not. Runbook: " + RUNBOOK)
                .strongReference(true).register(meters);
        Gauge.builder("tally.events.oldest_incomplete_age_seconds", jdbc, j -> oldestAgeSeconds(j, clock))
                .description("Age of the oldest incomplete publication; alert above 15 minutes (FR-022)."
                        + " Runbook: " + RUNBOOK)
                .baseUnit("seconds").strongReference(true).register(meters);
    }

    static long incomplete(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Long.class);
    }

    static double oldestAgeSeconds(JdbcTemplate jdbc, Clock clock) {
        OffsetDateTime oldest = jdbc.queryForObject(
                "SELECT min(publication_date) FROM event_publication WHERE completion_date IS NULL", OffsetDateTime.class);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }
}
