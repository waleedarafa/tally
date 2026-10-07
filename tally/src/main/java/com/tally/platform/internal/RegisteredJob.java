package com.tally.platform.internal;

import com.tally.platform.BackgroundJob;
import com.tally.platform.HealthSignal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A background job known to the platform, with its health signal (ADR-023): when it last
 * succeeded and how much work waits for it.
 *
 * @param registeredAt where the "last success" age counts from before the first success
 */
record RegisteredJob(String name, Instant registeredAt, Supplier<Optional<Instant>> lastSuccessSource,
        LongSupplier waitingSource) implements BackgroundJob, HealthSignal {

    @Override
    public Optional<Instant> lastSuccess() {
        return lastSuccessSource.get();
    }

    @Override
    public long waitingWork() {
        return waitingSource.getAsLong();
    }
}
