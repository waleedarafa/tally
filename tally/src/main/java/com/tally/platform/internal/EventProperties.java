package com.tally.platform.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Event publication resubmission ({@code tally.events.*}, FR-022).
 *
 * @param resubmitOlderThan  only publications older than this are resubmitted. Spike A showed that a
 *                           publication still running on another instance is resubmitted too, so this
 *                           must stay well above any listener's run time.
 * @param resubmitInterval   how often the resubmission job runs
 * @param listenerThreads    threads of the platform's event-listener executor (D13, R3); listeners
 *                           waiting for a thread are counted in {@code tally.events.listeners.queued}
 */
@ConfigurationProperties("tally.events")
public record EventProperties(
        @DefaultValue("5m") Duration resubmitOlderThan,
        @DefaultValue("1m") Duration resubmitInterval,
        @DefaultValue("8") int listenerThreads) {
}
