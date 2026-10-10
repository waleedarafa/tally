package com.tally.platform.internal;

import com.tally.platform.PeriodicJob;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;

/**
 * Resubmits incomplete event publications (FR-022), one publication at a time, so that one unreadable
 * publication never blocks the rest (ADR-016, AT-17). Spike findings decide how:
 * <ul>
 *   <li>{@code resubmitIncompletePublications(predicate)} (spike S-1, S1d): one pass that decides per
 *       publication. The predicate reads each event inside try/catch; a publication whose event cannot be
 *       read is skipped, logged and counted in {@code tally.events.unreadable}. It is never resubmitted:
 *       repairing it needs a person. {@code resubmitIncompletePublicationsOlderThan} stopped the whole
 *       run at the first unreadable row (first-run finding 1, S1a).</li>
 *   <li>A publication whose event class is gone never reaches the predicate: the registry drops it on
 *       load (S1f). Only {@code tally.events.incomplete}, counted in SQL, shows it.</li>
 *   <li>The predicate checks the minimum age: the predicate API has none of its own (S1g), and it covers
 *       publications a crash left in progress, as the first run's API did (Spike A).</li>
 *   <li>A periodic job on the platform's queue, so one instance resubmits at a time (two instances
 *       resubmitting together re-ran 9 to 12 of 20 publications, Spike A).</li>
 *   <li>The minimum age stays well above any listener's run time, because a publication still running
 *       elsewhere is resubmitted too.</li>
 *   <li>{@code republish-outstanding-events-on-restart} stays off: it re-runs work in flight elsewhere.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(EventProperties.class)
class EventResubmission implements PeriodicJob {

    private static final Logger log = LoggerFactory.getLogger(EventResubmission.class);

    private final IncompleteEventPublications incomplete;
    private final EventProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    /** Publications the last run skipped because their event could not be read. */
    private final AtomicLong unreadable = new AtomicLong();

    EventResubmission(IncompleteEventPublications incomplete, EventProperties properties, JdbcTemplate jdbc,
            Clock clock, MeterRegistry meters) {
        this.incomplete = incomplete;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("tally.events.unreadable", unreadable, AtomicLong::get).strongReference(true).register(meters);
    }

    @Override
    public String name() {
        return "event-resubmission";
    }

    @Override
    public String table() {
        return "platform.jobs";
    }

    @Override
    public Duration interval() {
        return properties.resubmitInterval();
    }

    @Override
    public void run() {
        Instant cutoff = clock.instant().minus(properties.resubmitOlderThan());
        AtomicLong skipped = new AtomicLong();
        incomplete.resubmitIncompletePublications(publication ->
                publication.getPublicationDate().isBefore(cutoff) && readable(publication, skipped));
        unreadable.set(skipped.get());
    }

    private static boolean readable(EventPublication publication, AtomicLong skipped) {
        try {
            publication.getEvent();
            return true;
        } catch (RuntimeException e) {
            skipped.incrementAndGet();
            log.error("Publication {} cannot be read and is not resubmitted; it needs a person (ADR-016)",
                    publication.getIdentifier(), e);
            return false;
        }
    }

    /** Incomplete publications, whatever their age, readable or not. */
    @Override
    public long waitingWork() {
        return EventHealth.incomplete(jdbc);
    }
}
