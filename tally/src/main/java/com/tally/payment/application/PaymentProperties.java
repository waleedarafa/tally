package com.tally.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for payment execution ({@code tally.payment.*}, brief section 5).
 *
 * @param workers   worker threads for collection attempts on each instance (the bulkhead)
 * @param instances how many Tally instances share the provider's rate limit
 */
@ConfigurationProperties("tally.payment")
public record PaymentProperties(
        @DefaultValue("15") int workers,
        @DefaultValue("2") int instances,
        @DefaultValue Provider provider,
        @DefaultValue Breaker breaker,
        @DefaultValue Reconciliation reconciliation) {

    /**
     * @param baseUrl            the provider's address
     * @param connectTimeout     brief section 5: 1 s
     * @param readTimeout        brief section 5: 5 s
     * @param rateLimitPerSecond the provider's limit for all instances together; each instance takes
     *                           its share ({@code rateLimitPerSecond / instances})
     * @param rateLimitWait      how long a worker may wait for a call permission before giving the
     *                           attempt back to the queue
     * @param rateLimitTargetPercent the share of the provider's limit Tally aims for, leaving headroom
     *                           for two instances' calls landing in one provider second (report R6)
     */
    public record Provider(
            @DefaultValue("http://localhost:8081") String baseUrl,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("100") int rateLimitPerSecond,
            @DefaultValue("2s") Duration rateLimitWait,
            @DefaultValue("95") int rateLimitTargetPercent) {
    }

    /**
     * The provider circuit breaker. While open, payment workers stop claiming.
     *
     * @param slidingWindow        calls the failure rate is measured over
     * @param minimumCalls         calls needed before the rate counts
     * @param failureRatePercent   failure rate that opens the breaker
     * @param openFor              how long it stays open before trial calls
     * @param trialCalls           calls allowed while half-open
     */
    public record Breaker(
            @DefaultValue("20") int slidingWindow,
            @DefaultValue("5") int minimumCalls,
            @DefaultValue("50") float failureRatePercent,
            @DefaultValue("30s") Duration openFor,
            @DefaultValue("3") int trialCalls) {
    }

    /** @param interval how often the reconciliation job runs (FR-018) */
    public record Reconciliation(@DefaultValue("1m") Duration interval) {
    }
}
