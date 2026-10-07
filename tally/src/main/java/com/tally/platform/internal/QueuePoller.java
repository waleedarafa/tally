package com.tally.platform.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the threads for one queue on this instance (ADR-027): a poller that claims due items,
 * a fixed worker pool that runs them (a bulkhead per queue), and the queue's reaper.
 * The poller claims no more items than it has free workers, so a claimed item never waits
 * locally long enough to be reaped from under it, and it keeps every worker busy while work is due.
 */
final class QueuePoller {

    private static final Logger log = LoggerFactory.getLogger(QueuePoller.class);

    private final JdbcDurableQueue queue;
    private final Clock clock;
    private final PlatformProperties properties;
    private final Semaphore freeWorkers;
    private final AtomicReference<Instant> lastPoll = new AtomicReference<>();
    private final AtomicReference<Instant> lastReap = new AtomicReference<>();
    private ScheduledExecutorService scheduler;
    private ExecutorService workers;

    QueuePoller(JdbcDurableQueue queue, Clock clock, PlatformProperties properties) {
        this.queue = queue;
        this.clock = clock;
        this.properties = properties;
        this.freeWorkers = new Semaphore(queue.definition().workers());
    }

    String pollerJobName() {
        return "queue-poller." + queue.name();
    }

    String reaperJobName() {
        return "queue-reaper." + queue.name();
    }

    Optional<Instant> lastPoll() {
        return Optional.ofNullable(lastPoll.get());
    }

    Optional<Instant> lastReap() {
        return Optional.ofNullable(lastReap.get());
    }

    synchronized void start() {
        if (scheduler != null) {
            return;
        }
        String name = queue.name();
        // Two threads: the poll loop can stay busy through a long backlog without holding up the reaper.
        scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("poller-" + name + "-", 1).factory());
        workers = Executors.newFixedThreadPool(queue.definition().workers(),
                Thread.ofPlatform().name("worker-" + name + "-", 1).factory());
        long poll = properties.pollInterval().toMillis();
        long reap = properties.reaperInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::poll, 0, poll, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::reap, reap, reap, TimeUnit.MILLISECONDS);
    }

    synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        workers.shutdown();
        try {
            // Wait for a poll cycle in progress, so nothing is claimed or recorded once stop returns.
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            // Items still running after this are returned by a reaper later.
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
        workers = null;
    }

    synchronized boolean isRunning() {
        return scheduler != null;
    }

    /**
     * Claims as long as there is due work: waits for a free worker, claims as many items as there are
     * free workers (up to the batch size), hands them out, and goes on until a claim comes back short
     * (the queue is drained, or its gate is closed). Every claim made counts as a successful cycle, so a
     * poller kept busy by a long backlog is not reported overdue; one stuck waiting for workers is.
     */
    private void poll() {
        int batch = queue.definition().batchSize();
        try {
            while (true) {
                freeWorkers.acquire();
                int held = 1 + freeWorkers.drainPermits();
                int capacity = Math.min(held, batch);
                freeWorkers.release(held - capacity);
                List<JdbcDurableQueue.Claim> claims = queue.claim(capacity);
                freeWorkers.release(capacity - claims.size());
                for (JdbcDurableQueue.Claim claim : claims) {
                    workers.execute(() -> {
                        try {
                            queue.handle(claim);
                        } finally {
                            freeWorkers.release();
                        }
                    });
                }
                lastPoll.set(clock.instant());
                if (claims.size() < capacity) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            // Stopping.
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Keep the poller alive; the job's "last success" age shows the trouble.
            log.error("Queue {}: poll failed", queue.name(), e);
        }
    }

    private void reap() {
        try {
            queue.reap();
            lastReap.set(clock.instant());
        } catch (RuntimeException e) {
            log.error("Queue {}: reaper failed", queue.name(), e);
        }
    }
}
