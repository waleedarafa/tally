package com.tally.entitlement.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * ADR-019 is Proposed, so its choice is a setting (brief section 3).
 *
 * @param grantBeforePayment grant access when the subscription starts ({@code true}), or only on
 *                           the first successful payment ({@code false})
 */
@ConfigurationProperties("tally.access")
public record AccessProperties(@DefaultValue("true") boolean grantBeforePayment) {
}
