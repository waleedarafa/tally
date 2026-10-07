package com.tally.platform.internal;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Starts a {@link QueuePoller} for every registered queue once the application is up, and stops
 * them on shutdown. The only place that owns threads for background work (ADR-027): there is
 * no {@code @Scheduled} anywhere (ADR-017). Each poller and reaper is a job with a health signal.
 */
@Component
class QueueRuntime implements SmartLifecycle {

    private final JdbcDurableQueues queues;
    private final JobRegistry jobs;
    private final Clock clock;
    private final PlatformProperties properties;
    private final Map<String, QueuePoller> pollers = new ConcurrentHashMap<>();
    private volatile boolean running;

    QueueRuntime(JdbcDurableQueues queues, JobRegistry jobs, Clock clock, PlatformProperties properties) {
        this.queues = queues;
        this.jobs = jobs;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (properties.pollingEnabled()) {
            queues.queues().forEach(q -> start(q.name()));
        }
        running = true;
    }

    @Override
    public void stop() {
        pollers.values().forEach(QueuePoller::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Starts polling one queue, registering its poller and reaper jobs on first start. */
    void start(String queueName) {
        QueuePoller poller = pollers.computeIfAbsent(queueName, name -> {
            JdbcDurableQueue queue = queues.queues().stream().filter(q -> q.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown queue " + name));
            QueuePoller created = new QueuePoller(queue, clock, properties);
            jobs.register(new RegisteredJob(created.pollerJobName(), properties.pollInterval(), clock.instant(),
                    created::lastPoll, queue::waiting));
            jobs.register(new RegisteredJob(created.reaperJobName(), properties.reaperInterval(), clock.instant(),
                    created::lastReap, () -> 0));
            return created;
        });
        poller.start();
    }

    /** Stops polling one queue. Its jobs stay registered, so their health shows them overdue (AT-11). */
    void stop(String queueName) {
        QueuePoller poller = pollers.get(queueName);
        if (poller != null) {
            poller.stop();
        }
    }
}
