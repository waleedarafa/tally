package com.tally.platform;

import java.time.Instant;
import java.util.Optional;

/** The health a background job reports: when it last succeeded and how much work is waiting (FR-023). */
public interface HealthSignal {

    /** Empty until the first success. */
    Optional<Instant> lastSuccess();

    long waitingWork();
}
