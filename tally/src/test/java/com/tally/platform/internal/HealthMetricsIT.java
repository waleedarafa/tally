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

/** ADR-023 and brief section 4: every queue and job reports its health as metrics. */
class HealthMetricsIT extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class QueueAndJob {

        @Bean
        DurableQueue healthQueue(DurableQueues queues) {
            return queues.register(QueueDefinition.of("test-health", "platform_test.health_queue"), item -> {
                if (item.key().startsWith("bad")) {
                    throw new IllegalStateException("bad item");
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

        // Nobody processes the queue: the oldest item keeps ageing (D7).
        clock.advance(Duration.ofMinutes(5));
        assertThat(gauge("tally.queue.oldest_due_age_seconds", "queue", "test-health")).isEqualTo(420);

        healthQueue.enqueue("bad-1", clock.instant(), "p");
        healthQueue.processDue();
        assertThat(gauge("tally.queue.waiting", "queue", "test-health")).isZero();
        assertThat(gauge("tally.queue.oldest_due_age_seconds", "queue", "test-health")).isZero();
        assertThat(gauge("tally.queue.failed", "queue", "test-health")).isEqualTo(1);
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

        // The job stops running: its last success ages (D13).
        clock.advance(Duration.ofMinutes(10));
        assertThat(gauge("tally.job.last_success_age_seconds", "job", "test-health-job")).isEqualTo(600);
    }
}
