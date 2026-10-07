package com.tally.invoicing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param billingWorkers worker threads for renewals on each instance */
@ConfigurationProperties("tally.invoicing")
public record BillingProperties(@DefaultValue("4") int billingWorkers) {
}
