package com.tally.platform.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for queue and job infrastructure ({@code tally.platform.*}).
 *
 * @param pollingEnabled whether pollers run; integration tests switch them off and drive queues directly
 * @param pollInterval   pause between claims when a queue has no due work
 * @param reaperInterval how often each queue's reaper runs
 * @param claimTimeout   how long an item may stay IN_PROCESSING before the reaper returns it
 */
@ConfigurationProperties("tally.platform")
public record PlatformProperties(
        @DefaultValue("true") boolean pollingEnabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("30s") Duration reaperInterval,
        @DefaultValue("5m") Duration claimTimeout) {
}
