package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.PeriodicJob;
import com.tally.platform.QueueDefinition;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-023 and brief section 4: every queue and job reports its health as metrics. */
class HealthMetricsIT extends AbstractIntegrationTest {

    /** A unique constraint on a test business table: the permanent duplicate guard (FR-037). */
    static final String GUARD = "platform_test.work_done_one_per_key";

    @TestConfiguration(proxyBeanMethods = false)
    static class QueueAndJob {

        @Bean
        DurableQueue healthQueue(DurableQueues queues) {
            return queues.register(QueueDefinition.of("test-health", "platform_test.health_queue", GUARD), item -> {
                if (item.key().startsWith("bad")) {
                    throw new IllegalStateException("bad item");
                }
                if (item.key().startsWith("later")) {
                    return Outcome.retryAt(item.dueAt().plus(Duration.ofHours(1)));
                }
                return Outcome.done();
            });
        }

        @Bean
        PeriodicJob healthJob() {
            return new PeriodicJob() {
                @Override
                public String name() {
                    return "test-health-job";
                }

                @Override
                public String table() {
                    return "platform_test.health_jobs";
                }

                @Override
                public Duration interval() {
                    return Duration.ofMinutes(1);
                }

                @Override
                public void run() {
                }

                @Override
                public long waitingWork() {
                    return 3;
                }
            };
        }
    }

    @Autowired
    DurableQueue healthQueue;

    @Autowired
    JdbcDurableQueues queues;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyQueue() {
        jdbc.update("DELETE FROM platform_test.health_queue");
    }

    double gauge(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).gauge().value();
    }

    @Test
    void queueMetricsShowWaitingWorkItsAgeAndFailures() {
        Instant now = clock.instant();
        healthQueue.enqueue("a", now.minus(Duration.ofMinutes(2)), "p");
        healthQueue.enqueue("b", now.minus(Duration.ofMinutes(1)), "p");
        healthQueue.enqueue("not-due", now.plus(Duration.ofHours(1)), "p");

        assertThat(gauge("tally.queue.waiting", "queue", "test-health")).isEqualTo(2);
        assertThat(gauge("tally.queue.oldest_due_age_seconds", "queue", "test-health")).isEqualTo(120);

        assertThat(gauge("tally.queue.overdue", "queue", "test-health")).isZero();

        // Nobody processes the queue: the oldest item keeps ageing, and is overdue past 5 minutes (FR-023).
        clock.advance(Duration.ofMinutes(5));
        assertThat(gauge("tally.queue.oldest_due_age_seconds", "queue", "test-health")).isEqualTo(420);
        assertThat(gauge("tally.queue.overdue", "queue", "test-health")).isEqualTo(1);

        healthQueue.enqueue("bad-1", clock.instant(), "p");
        healthQueue.processDue();
        assertThat(gauge("tally.queue.waiting", "queue", "test-health")).isZero();
        assertThat(gauge("tally.queue.oldest_due_age_seconds", "queue", "test-health")).isZero();
        assertThat(gauge("tally.queue.failed", "queue", "test-health")).isEqualTo(1);
    }

    @Test
    void lastProgressResetsWhenAnItemFinishesAndAgesWhileNothingFinishes() {
        healthQueue.enqueue("a", clock.instant(), "p");
        healthQueue.processDue();
        assertThat(gauge("tally.queue.last_progress_age_seconds", "queue", "test-health")).isZero();
        assertThat(meters.get("tally.queue.last_progress_age_seconds").tags("queue", "test-health", "money", "false")
                .gauge()).isNotNull();

        // Work waits and nothing finishes, not even an item a worker has claimed: progress keeps ageing (D9).
        healthQueue.enqueue("b", clock.instant(), "p");
        healthQueue.enqueue("c", clock.instant(), "p");
        clock.advance(Duration.ofMinutes(4));
        queues.queues().stream().filter(q -> q.name().equals("test-health")).findFirst().orElseThrow().claim(1);
        assertThat(gauge("tally.queue.waiting", "queue", "test-health")).isEqualTo(2);
        assertThat(gauge("tally.queue.last_progress_age_seconds", "queue", "test-health")).isEqualTo(240);

        // Every outcome that ends an item's processing is progress: done, failed, and returned to the queue.
        healthQueue.processDue();
        assertThat(gauge("tally.queue.last_progress_age_seconds", "queue", "test-health")).isZero();
        clock.advance(Duration.ofMinutes(1));
        healthQueue.enqueue("bad-1", clock.instant(), "p");
        healthQueue.processDue();
        assertThat(gauge("tally.queue.last_progress_age_seconds", "queue", "test-health")).isZero();
        clock.advance(Duration.ofMinutes(1));
        healthQueue.enqueue("later-1", clock.instant(), "p");
        healthQueue.processDue();
        assertThat(gauge("tally.queue.last_progress_age_seconds", "queue", "test-health")).isZero();
    }

    @Test
    void jobMetricsShowTheAgeOfTheLastSuccessAndWaitingWork() {
        JdbcDurableQueue jobs = queues.queues().stream()
                .filter(q -> q.name().equals(PeriodicJobs.queueName("platform_test.health_jobs"))).findFirst().orElseThrow();
        jdbc.update("UPDATE platform_test.health_jobs SET due_at = ? WHERE queue_key = 'test-health-job'",
                clock.instant().atOffset(ZoneOffset.UTC));

        jobs.processDue();
        assertThat(gauge("tally.job.last_success_age_seconds", "job", "test-health-job")).isZero();
        assertThat(gauge("tally.job.waiting", "job", "test-health-job")).isEqualTo(3);

        assertThat(gauge("tally.job.overdue", "job", "test-health-job")).isZero();

        // The job stops running: its last success ages (FR-023). Its interval is 1 minute, so it is
        // overdue after the 5-minute minimum (FR-023), not after 2 minutes.
        clock.advance(Duration.ofMinutes(5));
        assertThat(gauge("tally.job.overdue", "job", "test-health-job")).isZero();
        clock.advance(Duration.ofMinutes(5));
        assertThat(gauge("tally.job.last_success_age_seconds", "job", "test-health-job")).isEqualTo(600);
        assertThat(gauge("tally.job.overdue", "job", "test-health-job")).isEqualTo(1);
    }
}
