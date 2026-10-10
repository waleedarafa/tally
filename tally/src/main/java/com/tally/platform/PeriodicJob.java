package com.tally.platform;

import java.time.Duration;

/**
 * Work that repeats on an interval, such as reconciliation or an invariant check (FR-017, FR-027).
 * Declare it as a bean. Its row lives in the owning module's job table, keyed by {@link #name()},
 * and reschedules itself after every run; claiming with SKIP LOCKED means each run happens on
 * exactly one instance. A failed run is rescheduled too, but does not count as a success, so the
 * job's health shows it overdue (FR-023).
 */
public interface PeriodicJob {

    /** Stable name: the row's key and the {@code job} tag of its health metrics. */
    String name();

    /**
     * The owning module's job table, schema-qualified, with the shape described on
     * {@link QueueDefinition}. Several jobs of one module may share a table.
     */
    String table();

    Duration interval();

    void run() throws Exception;

    /** Work waiting for this job, reported in its health (FR-023). */
    default long waitingWork() {
        return 0;
    }
}
