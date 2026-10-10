package com.tally.platform.internal;

import com.tally.platform.BackgroundJob;
import com.tally.platform.HealthSignal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A background job known to the platform, with its health signal (FR-023): when it last
 * succeeded and how much work waits for it.
 *
 * @param interval     how often the job is meant to succeed
 * @param registeredAt where the "last success" age counts from before the first success
 */
record RegisteredJob(String name, Duration interval, Instant registeredAt,
        Supplier<Optional<Instant>> lastSuccessSource, LongSupplier waitingSource) implements BackgroundJob, HealthSignal {

    /** FR-023: a job is overdue by twice its interval, and never sooner than 5 minutes. */
    static final Duration MINIMUM_OVERDUE = Duration.ofMinutes(5);

    Duration overdueAfter() {
        Duration twice = interval.multipliedBy(2);
        return twice.compareTo(MINIMUM_OVERDUE) > 0 ? twice : MINIMUM_OVERDUE;
    }

    Duration lastSuccessAge(Instant now) {
        return Duration.between(lastSuccess().orElse(registeredAt), now);
    }

    boolean overdue(Instant now) {
        return lastSuccessAge(now).compareTo(overdueAfter()) > 0;
    }

    @Override
    public Optional<Instant> lastSuccess() {
        return lastSuccessSource.get();
    }

    @Override
    public long waitingWork() {
        return waitingSource.getAsLong();
    }
}
