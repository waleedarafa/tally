package com.tally.accessfeed.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The access feed's settings (ADR-033).
 *
 * @param log   where the facts go: the event log's brokers and the topic (one per environment; each test class
 *              has its own, ADR-029)
 * @param relay the relay that sends them
 */
@ConfigurationProperties("tally.accessfeed")
public record AccessFeedProperties(@DefaultValue Log log, @DefaultValue Relay relay) {

    /**
     * @param bootstrapServers the brokers, as Kafka's {@code bootstrap.servers}
     * @param topic            the compacted topic of current access facts, key = the account ID (ADR-012)
     * @param sendTimeout      the longest a send waits for the broker's acknowledgement, the first connection
     *                         included; the account's facts stay locked meanwhile, so keep it short
     * @param transactionTimeout how long the broker keeps an instance's open transaction before it aborts it; it
     *                         holds committed readers back, so keep it short (at least the send timeout)
     */
    public record Log(@DefaultValue("localhost:9092") String bootstrapServers,
            @DefaultValue("access-facts") String topic, @DefaultValue("5s") Duration sendTimeout,
            @DefaultValue("15s") Duration transactionTimeout) {
    }

    /**
     * @param workers    relay worker threads on each instance
     * @param retryAfter how long a change waits after the log did not acknowledge it
     */
    public record Relay(@DefaultValue("2") int workers, @DefaultValue("5s") Duration retryAfter) {
    }
}
