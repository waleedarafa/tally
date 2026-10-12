package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A poller stopped while a poll cycle is claiming, then started again, still claims (FR-027). The queue has one
 * worker, as every job table does. Its gate holds the poll thread inside the claim, as a slow database would,
 * while {@link QueuePoller#stop()} runs; interrupting the thread does not release it, as a JDBC call ignores
 * interrupts.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=50ms",
        "tally.test.context=queue-poller-restart"
})
class QueuePollerRestartIT extends AbstractIntegrationTest {

    static final Set<String> handled = ConcurrentHashMap.newKeySet();
    static volatile CountDownLatch inClaim = new CountDownLatch(0);
    static volatile CountDownLatch release = new CountDownLatch(0);

    static boolean holdInsideTheClaim() {
        inClaim.countDown();
        boolean interrupted = false;
        while (release.getCount() > 0) {
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OneWorkerQueue {

        @Bean
        DurableQueue restartedQueue(DurableQueues queues) {
            QueueDefinition definition = QueueDefinition.of("test-restarted", "platform_test.polled_queue",
                    QueuePollingIT.GUARD).withWorkers(1);
            return queues.register(definition, item -> {
                handled.add(item.key());
                return Outcome.done();
            }, QueuePollerRestartIT::holdInsideTheClaim);
        }
    }

    @Autowired
    DurableQueue restartedQueue;

    @Autowired
    QueueRuntime runtime;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM platform_test.polled_queue");
        handled.clear();
    }

    @AfterEach
    void releaseThePollThread() {
        release.countDown();
    }

    @Test
    void aPollerStoppedWhileAClaimIsInFlightClaimsAgainOnceStarted() throws InterruptedException {
        inClaim = new CountDownLatch(1);
        release = new CountDownLatch(1);
        restartedQueue.enqueue("in-flight", clock.instant(), "p");
        assertThat(inClaim.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Stop while the poll thread is inside the claim; stop waits for the poll cycle to end.
        Thread stopper = Thread.ofPlatform().start(() -> runtime.stop("test-restarted"));
        await().atMost(Duration.ofSeconds(5)).until(() -> stopper.getState() == Thread.State.TIMED_WAITING);
        release.countDown();
        stopper.join(Duration.ofSeconds(15));
        assertThat(stopper.isAlive()).isFalse();

        runtime.start("test-restarted");
        restartedQueue.enqueue("after-restart", clock.instant(), "p");

        await().atMost(Duration.ofSeconds(10)).until(() -> handled.contains("after-restart"));
        assertThat(handled).contains("in-flight");
    }
}
