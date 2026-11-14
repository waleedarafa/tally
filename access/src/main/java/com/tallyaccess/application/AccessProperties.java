package com.tallyaccess.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access's settings.
 *
 * @param log where the facts come from: the event log's brokers, the topic billing sends to (ADR-012), and the
 *            consumer group the instances share. The group only shares the partitions; the position is Access's own,
 *            in its database (ADR-035).
 */
@ConfigurationProperties("tally.access")
public record AccessProperties(@DefaultValue Log log) {

    /**
     * @param retryAfter how long Access waits after a failed read or apply before it reads again, from its stored
     *                   position
     */
    public record Log(@DefaultValue("localhost:9092") String bootstrapServers,
            @DefaultValue("access-facts") String topic, @DefaultValue("tally-access") String groupId,
            @DefaultValue("1s") Duration retryAfter) {
    }
}
