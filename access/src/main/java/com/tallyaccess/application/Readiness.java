package com.tallyaccess.application;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Readiness (brief PR-6): an instance whose copy is behind the end of the log it saw at start is not ready, and serves
 * no answer. Without this, a rebuild would answer NOT_KNOWN for paying households. Ready once the stored position of
 * every partition has reached the end that partition had when the instance started; it stays ready after that.
 *
 * <p>A copy that once reached that end is marked complete in Access's database (ADR-036).
 * An instance that finds the mark at start is ready at once, before it reads the log, and so also while the log is
 * down: it answers from its copy, which may be behind (D13). An emptied database has no mark, so a rebuild waits.
 *
 * <p>The platform asks {@code /actuator/health/readiness}; the health indicator {@code copyReady} reports this state
 * there, in the readiness group (application.yml). Spring Boot's own readiness state is not used for it: Spring Boot
 * reports "accepting traffic" once the application has started, which said yes while the copy was behind (found by
 * AT-19 in Slice 2 PR-7).
 */
@Component
public class Readiness {

    private final AtomicReference<Map<Integer, Long>> endAtStart = new AtomicReference<>();
    private volatile boolean ready;

    public boolean ready() {
        return ready;
    }

    /** The copy was complete once: ready at once, whatever the log says. */
    public void completeBefore() {
        ready = true;
    }

    /** The end of each partition, read committed, when the instance started; set once. */
    public void endAtStart(Map<Integer, Long> ends) {
        endAtStart.compareAndSet(null, Map.copyOf(ends));
    }

    /** Checks the stored positions against the end at start; ready the first time they reach it. */
    public void check(Map<Integer, Long> positions) {
        Map<Integer, Long> ends = endAtStart.get();
        if (ready || ends == null) {
            return;
        }
        ready = ends.entrySet().stream()
                .allMatch(end -> positions.getOrDefault(end.getKey(), 0L) >= end.getValue());
    }
}
