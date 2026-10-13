package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.payment.PaymentApi;
import com.tally.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * AT-11. Stop each worker and job (D9, FR-023). Pass when: each one's metric shows it overdue
 * within its threshold: twice its interval, and at least 5 minutes.
 *
 * <p>Covers every job the running application has: each queue's poller (the workers) and reaper, and
 * every periodic job (reconciliation, event resubmission, the invariant checks). Lives in the
 * platform's package because stopping pollers is not public API.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=at11"
})
class AT11StopEachWorkerAndJobIT extends AbstractIntegrationTest {

    static final List<String> JOB_TABLES = List.of("platform.jobs", "payment.jobs", "invoicing.jobs");

    @Autowired
    JobRegistry registry;

    @Autowired
    QueueRuntime runtime;

    @Autowired
    JdbcDurableQueues queues;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentApi payments;

    double overdue(String job) {
        return meters.get("tally.job.overdue").tag("job", job).gauge().value();
    }

    Map<String, Double> overdueByJob() {
        Map<String, Double> all = new TreeMap<>();
        registry.jobs().forEach(job -> all.put(job.name(), overdue(job.name())));
        return all;
    }

    @Test
    void everyStoppedWorkerAndJobShowsOverdueWithinItsThreshold() {
        // Job rows are shared by every test context: bring them all due at this context's clock and let them run.
        Instant start = clock.instant();
        for (String table : JOB_TABLES) {
            jdbc.update("UPDATE " + table + " SET status = 'READY', claim_token = NULL, due_at = ?, last_success_at = NULL",
                    start.atOffset(ZoneOffset.UTC));
        }
        List<RegisteredJob> periodic = registry.jobs().stream()
                .filter(j -> !j.name().startsWith("queue-")).toList();
        await().atMost(Duration.ofSeconds(20)).until(() -> periodic.stream()
                .allMatch(j -> j.lastSuccess().map(at -> !at.isBefore(start)).orElse(false)));
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.jobs().stream()
                .filter(j -> j.name().startsWith("queue-poller")).allMatch(j -> j.lastSuccess().isPresent()));
        System.out.println("AT-11 jobs: " + registry.jobs().stream()
                .map(j -> j.name() + " (overdue after " + j.overdueAfter() + ")").toList());
        assertThat(registry.jobs()).hasSizeGreaterThanOrEqualTo(15);

        // Running normally, nothing is overdue.
        assertThat(overdueByJob()).allSatisfy((job, value) -> assertThat(value).as(job).isZero());

        // Stop every worker and job.
        queues.queues().forEach(q -> runtime.stop(q.name()));
        // Work keeps arriving: a collection request waits in the stopped payment queue.
        payments.requestCollection("AT11-" + UUID.randomUUID(), UUID.randomUUID(), Money.of(999, "USD"), "tok");

        try {
            // Before any threshold: no alert yet.
            clock.advance(Duration.ofMinutes(4));
            assertThat(overdueByJob()).allSatisfy((job, value) -> assertThat(value).as(job).isZero());
            assertThat(meters.get("tally.queue.overdue").tag("queue", "payment-collection").gauge().value()).isZero();

            // Just past 5 minutes: every job whose threshold is 5 minutes is overdue, and so is the waiting
            // payment (D9); jobs with longer intervals (hourly retention, daily invariant checks) are not, yet.
            Duration stopped = Duration.ofMinutes(5).plusSeconds(1);
            clock.advance(Duration.ofMinutes(1).plusSeconds(1));
            Map<String, Double> afterFiveMinutes = overdueByJob();
            registry.jobs().forEach(job -> assertThat(afterFiveMinutes.get(job.name())).as(job.name())
                    .isEqualTo(job.overdueAfter().compareTo(stopped) <= 0 ? 1 : 0));
            assertThat(afterFiveMinutes).as("jobs overdue after 5 minutes").containsValue(1.0);
            assertThat(meters.get("tally.queue.overdue").tag("queue", "payment-collection").gauge().value())
                    .isEqualTo(1);

            // Just past twice the daily interval: the invariant checks are overdue too.
            clock.set(start.plus(Duration.ofDays(2)).plusSeconds(1));
            assertThat(overdueByJob()).allSatisfy((job, value) -> assertThat(value).as(job).isEqualTo(1));
            System.out.println("AT-11: every stopped worker and job overdue: " + overdueByJob());
        } finally {
            queues.queues().forEach(q -> runtime.start(q.name()));
        }
    }
}
