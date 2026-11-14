package com.tallyaccess.application;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Readiness (brief PR-6): an instance whose copy is behind the end of the log it saw at start is not ready, and serves
 * no answer. Without this, a rebuild would answer NOT_KNOWN for paying households. Ready once the stored position of
 * every partition has reached the end that partition had when the instance started; it stays ready after that.
 * Reported as Spring Boot's readiness state ({@code /actuator/health/readiness}).
 *
 * <p>A copy that once reached that end is marked complete in Access's database (review of PR-6, finding 2, option B).
 * An instance that finds the mark at start is ready at once, before it reads the log, and so also while the log is
 * down: it answers from its copy, which may be behind (D13). An emptied database has no mark, so a rebuild waits.
 */
@Component
public class Readiness {

    private final ApplicationEventPublisher events;
    private final AtomicReference<Map<Integer, Long>> endAtStart = new AtomicReference<>();
    private volatile boolean ready;

    Readiness(ApplicationEventPublisher events) {
        this.events = events;
    }

    public boolean ready() {
        return ready;
    }

    /** The copy was complete once: ready at once, whatever the log says. */
    public void completeBefore() {
        becomeReady();
    }

    /** The end of each partition, read committed, when the instance started; set once. */
    public void endAtStart(Map<Integer, Long> ends) {
        endAtStart.compareAndSet(null, Map.copyOf(ends));
        if (!ready) {
            AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    /** Checks the stored positions against the end at start; reports ready the first time they reach it. */
    public void check(Map<Integer, Long> positions) {
        Map<Integer, Long> ends = endAtStart.get();
        if (ready || ends == null) {
            return;
        }
        boolean caughtUp = ends.entrySet().stream()
                .allMatch(end -> positions.getOrDefault(end.getKey(), 0L) >= end.getValue());
        if (caughtUp) {
            becomeReady();
        }
    }

    private void becomeReady() {
        ready = true;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
    }
}
