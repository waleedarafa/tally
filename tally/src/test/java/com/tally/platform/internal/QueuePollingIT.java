package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Pollers and worker pools run queues in the background (ADR-017, ADR-027). */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=50ms"
})
class QueuePollingIT extends AbstractIntegrationTest {

    /** A unique constraint on a test business table: the permanent duplicate guard (ADR-037). */
    static final String GUARD = "platform_test.work_done_one_per_key";

    static final Set<String> handled = ConcurrentHashMap.newKeySet();
    static volatile CountDownLatch gate = new CountDownLatch(0);

    @TestConfiguration(proxyBeanMethods = false)
    static class PolledQueue {

        @Bean
        DurableQueue polledQueue(DurableQueues queues) {
            QueueDefinition definition = QueueDefinition.of("test-polled", "platform_test.polled_queue", GUARD).withWorkers(2);
            return queues.register(definition, item -> {
                gate.await(30, TimeUnit.SECONDS);
                handled.add(item.key());
                return Outcome.done();
            });
        }
    }

    @Autowired
    DurableQueue polledQueue;

    @Autowired
    QueueRuntime runtime;

    @Autowired
    JobRegistry jobs;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM platform_test.polled_queue");
        handled.clear();
        gate = new CountDownLatch(0);
        runtime.start("test-polled");
    }

    @AfterEach
    void releaseWorkers() {
        gate.countDown();
    }

    int inProcessing() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform_test.polled_queue WHERE status = 'IN_PROCESSING'", Integer.class);
    }

    @Test
    void dueItemsAreHandledInTheBackground() {
        for (int i = 0; i < 20; i++) {
            polledQueue.enqueue("item-" + i, clock.instant(), "p");
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> handled.size() == 20);
    }

    @Test
    void theWorkerPoolIsABulkheadAndNothingIsClaimedBeyondFreeWorkers() {
        gate = new CountDownLatch(1);
        for (int i = 0; i < 5; i++) {
            polledQueue.enqueue("item-" + i, clock.instant(), "p");
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> inProcessing() == 2);
        // Several more poll cycles pass: still only two claimed, three waiting.
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> inProcessing() == 2);

        gate.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> handled.size() == 5);
    }

    @Test
    void aStoppedPollerStopsSucceeding() {
        await().atMost(Duration.ofSeconds(5))
                .until(() -> jobs.find("queue-poller.test-polled").orElseThrow().lastSuccess().isPresent());

        runtime.stop("test-polled");
        Instant lastSuccess = jobs.find("queue-poller.test-polled").orElseThrow().lastSuccess().orElseThrow();
        clock.advance(Duration.ofMinutes(10));
        polledQueue.enqueue("after-stop", clock.instant(), "p");

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> handled.isEmpty());
        assertThat(jobs.find("queue-poller.test-polled").orElseThrow().lastSuccess()).contains(lastSuccess);
        assertThat(jobs.find("queue-poller.test-polled").orElseThrow().waitingWork()).isEqualTo(1);
        assertThat(meters.get("tally.job.last_success_age_seconds").tag("job", "queue-poller.test-polled")
                .gauge().value()).isGreaterThanOrEqualTo(600);
        assertThat(meters.get("tally.queue.waiting").tag("queue", "test-polled").gauge().value()).isEqualTo(1);
    }
}
