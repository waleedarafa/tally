package com.tally.subscription.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param renewalWorkers worker threads for renewals on each instance
 * @param blockedRecheck how long a renewal waits before it looks at a BLOCKED account's standing again (decision 3 of
 *                       the Slice 3 brief); the renewal runs within this time of the standing being GOOD again
 */
@ConfigurationProperties("tally.subscription")
public record RenewalProperties(@DefaultValue("4") int renewalWorkers, @DefaultValue("1h") Duration blockedRecheck) {
}
