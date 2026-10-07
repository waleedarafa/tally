package com.tally.platform.internal;

import com.tally.platform.PeriodicJob;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;

/**
 * Resubmits incomplete event publications (ADR-022). Spike A findings decide how:
 * <ul>
 *   <li>{@code resubmitIncompletePublicationsOlderThan}: the only API that also covers publications a
 *       crash left PUBLISHED or PROCESSING ({@code ResubmissionOptions} covers FAILED only);</li>
 *   <li>a periodic job on the platform's queue, so one instance resubmits at a time (two instances
 *       resubmitting together re-ran 9 to 12 of 20 publications);</li>
 *   <li>a minimum age well above any listener's run time, because a publication still running elsewhere
 *       is resubmitted too;</li>
 *   <li>{@code republish-outstanding-events-on-restart} stays off: it re-runs work in flight elsewhere.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(EventProperties.class)
class EventResubmission implements PeriodicJob {

    private final IncompleteEventPublications incomplete;
    private final EventProperties properties;
    private final JdbcTemplate jdbc;

    EventResubmission(IncompleteEventPublications incomplete, EventProperties properties, JdbcTemplate jdbc) {
        this.incomplete = incomplete;
        this.properties = properties;
        this.jdbc = jdbc;
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
        incomplete.resubmitIncompletePublicationsOlderThan(properties.resubmitOlderThan());
    }

    /** Incomplete publications, whatever their age. */
    @Override
    public long waitingWork() {
        return EventHealth.incomplete(jdbc);
    }
}
