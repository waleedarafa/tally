package com.tally.platform.internal;

import com.tally.platform.ClaimGate;
import com.tally.platform.Outcome;
import com.tally.platform.PeriodicJob;
import com.tally.platform.QueueDefinition;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Turns every {@link PeriodicJob} bean into a self-rescheduling row in its module's job table.
 * Each job table becomes one queue, polled like any other; its rows are dispatched by key.
 * Runs before the pollers start.
 */
@Component
class PeriodicJobs implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PeriodicJobs.class);

    private final ObjectProvider<PeriodicJob> beans;
    private final JdbcDurableQueues queues;
    private final JobRegistry registry;
    private final Clock clock;

    PeriodicJobs(ObjectProvider<PeriodicJob> beans, JdbcDurableQueues queues, JobRegistry registry, Clock clock) {
        this.beans = beans;
        this.queues = queues;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, List<PeriodicJob>> byTable = beans.orderedStream()
                .collect(Collectors.groupingBy(PeriodicJob::table));
        byTable.forEach((table, jobs) -> {
            Map<String, PeriodicJob> byName = jobs.stream()
                    .collect(Collectors.toMap(PeriodicJob::name, Function.identity()));
            JdbcDurableQueue queue = queues.register(
                    QueueDefinition.of(queueName(table), table), processor(byName), ClaimGate.ALWAYS_OPEN);
            jobs.forEach(job -> schedule(queue, job));
        });
    }

    static String queueName(String table) {
        return "jobs-" + table.replace('.', '-').replace('_', '-');
    }

    /** Ensures the job's row exists (due now if new) and registers the job's health signal. */
    private void schedule(JdbcDurableQueue queue, PeriodicJob job) {
        queue.enqueue(job.name(), clock.instant(), "");
        registry.register(new RegisteredJob(job.name(), job.interval(),
                queue.createdAt(job.name()).orElse(clock.instant()),
                () -> queue.lastSuccess(job.name()),
                job::waitingWork));
    }

    static JdbcDurableQueue.Processor processor(Map<String, PeriodicJob> jobs) {
        return (queue, claim) -> {
            PeriodicJob job = jobs.get(claim.item().key());
            if (job == null) {
                // A job removed from the code: leave its row finished, do not run it.
                log.warn("Queue {}: no periodic job named {}", queue.name(), claim.item().key());
                queue.complete(claim, Outcome.done());
                return;
            }
            Optional<Exception> error = Optional.empty();
            try {
                job.run();
            } catch (Exception e) {
                log.error("Periodic job {} failed", job.name(), e);
                error = Optional.of(e);
            }
            queue.reschedule(claim, queue.now().plus(job.interval()), error);
        };
    }
}
