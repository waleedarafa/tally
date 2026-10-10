package com.tally.subscription.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param renewalWorkers worker threads for renewals on each instance */
@ConfigurationProperties("tally.subscription")
public record RenewalProperties(@DefaultValue("4") int renewalWorkers) {
}
