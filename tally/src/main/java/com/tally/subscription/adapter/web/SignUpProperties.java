package com.tally.subscription.adapter.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-up over REST ({@code tally.subscription.sign-up.*}).
 *
 * @param requestTimeout how long a request may hold its Idempotency-Key without storing a response. Past it,
 *                       the request died, and the next request with the key takes it over (PR #23 review).
 */
@ConfigurationProperties("tally.subscription.sign-up")
public record SignUpProperties(@DefaultValue("60s") Duration requestTimeout) {
}
