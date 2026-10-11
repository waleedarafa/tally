package com.tally.support;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A scheduled job whose row is shared by every cached test context. All contexts use one database, and each
 * runs its own job runner on its own {@code TestClock}: a context whose clock is ahead can claim the row and
 * reschedule it into its own future, where this context's runner never sees it due (Slice 1R report §7). A
 * test that waits for the scheduled job to act re-arms the row at its own clock on every poll, so the job
 * still runs on schedule, end to end, and another context's reschedule cannot strand it.
 */
public record SharedJob(String table, String key) {

    public static final SharedJob RECONCILIATION = new SharedJob("payment.jobs", "payment-reconciliation");
    public static final SharedJob EVENT_RESUBMISSION = new SharedJob("platform.jobs", "event-resubmission");

    public static SharedJob invariantCheck(String key) {
        return new SharedJob("invoicing.jobs", key);
    }

    /** Makes the job due now at this context's clock, if it waits for a later time. A claimed job is left alone. */
    public void rearm(JdbcTemplate jdbc, Clock clock) {
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("UPDATE " + table + " SET due_at = ? WHERE queue_key = ? AND status = 'READY' AND due_at > ?",
                now, key, now);
    }

    /** Waits until {@code done} holds, re-arming the job at this context's clock on every poll. */
    public void await(JdbcTemplate jdbc, Clock clock, Duration atMost, Callable<Boolean> done) {
        Awaitility.await().atMost(atMost).pollInterval(Duration.ofMillis(100)).until(() -> {
            rearm(jdbc, clock);
            return done.call();
        });
    }
}
