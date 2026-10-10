package com.tally.checkout.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * FR-019 is Proposed, so its choice is a setting (brief section 3).
 *
 * @param paymentWait how long checkout waits for a payment result before answering PROCESSING
 */
@ConfigurationProperties("tally.checkout")
public record CheckoutProperties(@DefaultValue("3s") Duration paymentWait) {
}
