package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.BackgroundJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Fitness function FF-10b (ADR-023), the runtime half of FF-10: in the running application,
 * every background job has a {@code tally.job.last_success_age_seconds} metric, and every queue
 * has its queue metrics. Covers jobs the platform registers (pollers, reapers, periodic jobs)
 * and any {@link BackgroundJob} bean, so a job that runs outside the platform is caught too.
 * Polling is on, so the jobs of every production queue are registered.
 */
@TestPropertySource(properties = "tally.platform.polling-enabled=true")
class EveryJobHasHealthMetricsIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    JobRegistry registry;

    @Autowired
    JdbcDurableQueues queues;

    @Autowired
    MeterRegistry meters;

    @Test
    void everyJobAndQueueHasItsHealthMetrics() {
        Set<String> jobs = new TreeSet<>();
        registry.jobs().forEach(job -> jobs.add(job.name()));
        context.getBeansOfType(BackgroundJob.class).values().forEach(job -> jobs.add(job.name()));

        List<String> missing = new ArrayList<>();
        for (String job : jobs) {
            for (String metric : List.of("tally.job.last_success_age_seconds", "tally.job.overdue")) {
                if (meters.find(metric).tag("job", job).gauge() == null) {
                    missing.add("job " + job + ": " + metric);
                }
            }
        }
        for (JdbcDurableQueue queue : queues.queues()) {
            for (String metric : List.of("tally.queue.oldest_due_age_seconds", "tally.queue.waiting")) {
                if (meters.find(metric).tag("queue", queue.name()).gauge() == null) {
                    missing.add("queue " + queue.name() + ": " + metric);
                }
            }
        }

        assertThat(missing).as("Every background job and queue reports health (ADR-023)").isEmpty();
    }
}
