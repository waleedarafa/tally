package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.PeriodicJob;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Periodic jobs are rows that reschedule themselves, run once per due time across instances. */
class PeriodicJobIT extends AbstractIntegrationTest {

    static final String TABLE = "platform_test.jobs";

    static class CountingJob implements PeriodicJob {
        final String name;
        final AtomicInteger runs = new AtomicInteger();
        final AtomicBoolean failing = new AtomicBoolean();

        CountingJob(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String table() {
            return TABLE;
        }

        @Override
        public Duration interval() {
            return Duration.ofMinutes(5);
        }

        @Override
        public void run() {
            runs.incrementAndGet();
            if (failing.get()) {
                throw new IllegalStateException("job failed");
            }
        }

        @Override
        public long waitingWork() {
            return 7;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Jobs {
        @Bean
        CountingJob reconciliation() {
            return new CountingJob("test-reconciliation");
        }
    }

    @Autowired
    CountingJob job;

    @Autowired
    JdbcDurableQueues queues;

    @Autowired
    JobRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    JdbcDurableQueue jobsQueue;

    @BeforeEach
    void setUp() {
        jobsQueue = queues.queues().stream()
                .filter(q -> q.name().equals(PeriodicJobs.queueName(TABLE))).findFirst().orElseThrow();
        // Bring the job row due now, whatever earlier tests left behind.
        jdbc.update("UPDATE " + TABLE + " SET status = 'READY', due_at = ?, claim_token = NULL WHERE queue_key = ?",
                clock.instant().atOffset(ZoneOffset.UTC), job.name());
        job.runs.set(0);
        job.failing.set(false);
    }

    Instant dueAt() {
        return jdbc.queryForObject("SELECT due_at FROM " + TABLE + " WHERE queue_key = ?",
                OffsetDateTime.class, job.name()).toInstant();
    }

    @Test
    void theJobRowExistsOnceAndCarriesTheJobHealth() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE queue_key = ?",
                Integer.class, job.name())).isEqualTo(1);
        RegisteredJob health = registry.find("test-reconciliation").orElseThrow();
        assertThat(health.waitingWork()).isEqualTo(7);
    }

    @Test
    void aRunReschedulesTheJobAndRecordsItsSuccess() {
        Instant now = clock.instant();

        assertThat(jobsQueue.processDue()).isEqualTo(1);

        assertThat(job.runs).hasValue(1);
        assertThat(dueAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
        assertThat(registry.find("test-reconciliation").orElseThrow().lastSuccess()).contains(now);
        assertThat(jobsQueue.processDue()).isZero();

        clock.advance(Duration.ofMinutes(5));
        assertThat(jobsQueue.processDue()).isEqualTo(1);
        assertThat(job.runs).hasValue(2);
    }

    @Test
    void twoInstancesRunADueJobOnce() throws Exception {
        var processor = PeriodicJobs.processor(Map.of(job.name(), job));
        var definition = jobsQueue.definition();
        List<Thread> threads = new ArrayList<>();
        for (String instance : List.of("instance-a", "instance-b")) {
            JdbcDurableQueue queue = queues.create(definition, processor, instance);
            for (int i = 0; i < 4; i++) {
                threads.add(Thread.ofPlatform().start(queue::processDue));
            }
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(job.runs).hasValue(1);
    }

    @Test
    void aFailedRunIsRescheduledButIsNotASuccess() {
        jobsQueue.processDue();
        Instant firstSuccess = registry.find("test-reconciliation").orElseThrow().lastSuccess().orElseThrow();
        job.failing.set(true);
        clock.advance(Duration.ofMinutes(5));

        assertThat(jobsQueue.processDue()).isEqualTo(1);

        assertThat(dueAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(jdbc.queryForObject("SELECT status FROM " + TABLE + " WHERE queue_key = ?",
                String.class, job.name())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLE + " WHERE queue_key = ?",
                String.class, job.name())).contains("job failed");
        assertThat(registry.find("test-reconciliation").orElseThrow().lastSuccess()).contains(firstSuccess);
    }
}
