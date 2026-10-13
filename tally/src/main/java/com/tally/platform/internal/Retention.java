package com.tally.platform.internal;

import com.tally.platform.PeriodicJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.stereotype.Component;

/**
 * Deletes finished infrastructure rows once they are older than {@code tally.retention.keep}
 * (slice 1 report Q5). A periodic job on the platform's queue, so one instance runs it at a time
 * (FR-017), with the usual job health (FR-023): {@code tally.job.*{job="retention"}}, whose
 * waiting work is the number of rows due for deletion.
 * <ul>
 *   <li>Queue items with status {@code DONE}, in every registered queue, claimed before the cutoff.
 *       The claim time is when the item was last taken for processing, so at most moments before
 *       it finished. {@code READY}, {@code IN_PROCESSING} and {@code FAILED} items are never
 *       deleted; failed items wait for a person (slice 1 review, D12). Periodic job rows never become
 *       {@code DONE}.</li>
 *   <li>Completed event publications, through Spring Modulith's {@link CompletedEventPublications}.
 *       Incomplete publications are never deleted; FR-022's resubmission owns them.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(RetentionProperties.class)
class Retention implements PeriodicJob {

    private static final Logger log = LoggerFactory.getLogger(Retention.class);

    private final JdbcDurableQueues queues;
    private final CompletedEventPublications completed;
    private final RetentionProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    Retention(JdbcDurableQueues queues, CompletedEventPublications completed, RetentionProperties properties,
            JdbcTemplate jdbc, Clock clock) {
        this.queues = queues;
        this.completed = completed;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "retention";
    }

    @Override
    public String table() {
        return "platform.jobs";
    }

    @Override
    public Duration interval() {
        return properties.interval();
    }

    @Override
    public void run() {
        OffsetDateTime cutoff = cutoff();
        for (JdbcDurableQueue queue : queues.queues()) {
            String table = queue.definition().table();
            long deleted = 0;
            int batch;
            do {
                batch = jdbc.update("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                        + " WHERE status = 'DONE' AND claimed_at < ? LIMIT ?)", cutoff, properties.batch());
                deleted += batch;
            } while (batch == properties.batch());
            if (deleted > 0) {
                log.info("Retention: deleted {} finished items from queue {}", deleted, queue.name());
            }
        }
        completed.deletePublicationsOlderThan(properties.keep());
    }

    /** Rows due for deletion: finished queue items and completed publications past the cutoff. */
    @Override
    public long waitingWork() {
        OffsetDateTime cutoff = cutoff();
        long waiting = jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE completion_date < ?", Long.class, cutoff);
        for (JdbcDurableQueue queue : queues.queues()) {
            waiting += jdbc.queryForObject("SELECT count(*) FROM " + queue.definition().table()
                    + " WHERE status = 'DONE' AND claimed_at < ?", Long.class, cutoff);
        }
        return waiting;
    }

    private OffsetDateTime cutoff() {
        Instant cutoff = clock.instant().minus(properties.keep());
        return cutoff.atOffset(ZoneOffset.UTC);
    }
}
