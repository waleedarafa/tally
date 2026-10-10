package com.tally.platform;

/**
 * A unit of background work run by the platform: a queue handler or a periodic job (FR-027).
 * Every implementation must also be a {@link HealthSignal} (FR-023); a fitness function checks this.
 */
public interface BackgroundJob {

    /** Stable name, used as the {@code job} or {@code queue} tag of its health metrics. */
    String name();
}
