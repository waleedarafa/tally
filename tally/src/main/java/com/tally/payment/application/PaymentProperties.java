package com.tally.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for payment execution ({@code tally.payment.*}, brief section 5).
 *
 * @param workers worker threads for collection attempts on each instance (a bulkhead)
 */
@ConfigurationProperties("tally.payment")
public record PaymentProperties(@DefaultValue("15") int workers, @DefaultValue Provider provider) {

    /**
     * @param baseUrl        the provider's address
     * @param connectTimeout brief section 5: 1 s
     * @param readTimeout    brief section 5: 5 s
     */
    public record Provider(
            @DefaultValue("http://localhost:8081") String baseUrl,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout) {
    }
}
