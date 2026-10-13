package com.tally.platform.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Health of every queue and background job, as Micrometer gauges (FR-023, D9, D13).
 * Values are read when scraped, from the database where they are shared across instances,
 * so a stopped poller or job keeps ageing in its metric instead of freezing.
 * <ul>
 *   <li>{@code tally.queue.oldest_due_age_seconds{queue}}: age of the oldest due, unfinished item</li>
 *   <li>{@code tally.queue.waiting{queue}}: due, unfinished items</li>
 *   <li>{@code tally.queue.failed{queue}}: items that failed and wait for a person</li>
 *   <li>{@code tally.queue.last_progress_age_seconds{queue}}: since an item's processing last ended, whatever
 *       the outcome. Work waiting with no progress is a stalled billing run (D9); a busy queue keeps it near 0</li>
 *   <li>{@code tally.job.last_success_age_seconds{job}}: since the last success, or since
 *       registration if the job has never succeeded</li>
 *   <li>{@code tally.job.waiting{job}}: work waiting for the job</li>
 *   <li>{@code tally.job.overdue{job}}: 1 when the last success is older than twice the job's interval,
 *       and at least 5 minutes (FR-023); otherwise 0</li>
 *   <li>{@code tally.queue.overdue{queue}}: 1 when the oldest due item is 5 minutes late (FR-023); otherwise 0.
 *       Not used for paging: on a busy billing day the oldest due item is legitimately hours old (D9)</li>
 *   <li>every queue gauge also carries {@code money}: {@code true} for a money queue (FR-037)</li>
 * </ul>
 */
@Component
class HealthMetrics {

    /** FR-023: a queue is overdue when its oldest due item is 5 minutes late. */
    static final double QUEUE_OVERDUE_SECONDS = 300;

    private final MeterRegistry meters;
    private final Clock clock;

    HealthMetrics(MeterRegistry meters, Clock clock, JdbcDurableQueues queues, JobRegistry jobs) {
        this.meters = meters;
        this.clock = clock;
        queues.onRegistered(this::bindQueue);
        jobs.onRegistered(this::bindJob);
    }

    private void bindQueue(JdbcDurableQueue queue) {
        // money="true" marks a money queue (FR-037): a failed item there must alert (slice 2 alert rules).
        Tags tags = Tags.of("queue", queue.name(), "money", String.valueOf(queue.definition().money()));
        Gauge.builder("tally.queue.oldest_due_age_seconds", queue, JdbcDurableQueue::oldestDueAgeSeconds)
                .tags(tags).baseUnit("seconds").strongReference(true).register(meters);
        Gauge.builder("tally.queue.waiting", queue, JdbcDurableQueue::waiting)
                .tags(tags).strongReference(true).register(meters);
        Gauge.builder("tally.queue.failed", queue, JdbcDurableQueue::failed)
                .tags(tags).strongReference(true).register(meters);
        Gauge.builder("tally.queue.overdue", queue, q -> q.oldestDueAgeSeconds() >= QUEUE_OVERDUE_SECONDS ? 1 : 0)
                .tags(tags).strongReference(true).register(meters);
        Gauge.builder("tally.queue.last_progress_age_seconds", queue, JdbcDurableQueue::lastProgressAgeSeconds)
                .tags(tags).baseUnit("seconds").strongReference(true).register(meters);
    }

    private void bindJob(RegisteredJob job) {
        Gauge.builder("tally.job.last_success_age_seconds", job, this::lastSuccessAgeSeconds)
                .tag("job", job.name()).baseUnit("seconds").strongReference(true).register(meters);
        Gauge.builder("tally.job.waiting", job, RegisteredJob::waitingWork)
                .tag("job", job.name()).strongReference(true).register(meters);
        Gauge.builder("tally.job.overdue", job, j -> j.overdue(clock.instant()) ? 1 : 0)
                .tag("job", job.name()).strongReference(true).register(meters);
    }

    private double lastSuccessAgeSeconds(RegisteredJob job) {
        return job.lastSuccessAge(clock.instant()).toMillis() / 1000.0;
    }
}
