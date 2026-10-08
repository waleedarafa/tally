package com.tally.platform.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention of finished infrastructure rows ({@code tally.retention.*}; slice 1 report Q5).
 *
 * @param keep     how long finished queue items and completed event publications are kept. A queue
 *                 item's key stops guarding against a duplicate once its row is deleted, so this must
 *                 stay far above any window in which the same key could be enqueued again.
 * @param interval how often the retention job runs
 * @param batch    rows deleted per statement, so no delete holds locks for long
 */
@ConfigurationProperties("tally.retention")
public record RetentionProperties(
        @DefaultValue("30d") Duration keep,
        @DefaultValue("1h") Duration interval,
        @DefaultValue("10000") int batch) {
}
