package spike.modulith;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Spike A's listener. What it does for each event is set by the test, per event ID, and can change
 * between deliveries: SUCCEED, FAIL (throws), or BLOCK (waits, ignoring interrupts, as a listener
 * stuck in I/O would, until released). Counts every invocation, across all application contexts.
 */
public class SpikeListener {

    public enum Behaviour { SUCCEED, FAIL, BLOCK }

    public static final Map<UUID, Behaviour> BEHAVIOUR = new ConcurrentHashMap<>();
    public static final Map<UUID, AtomicInteger> INVOCATIONS = new ConcurrentHashMap<>();
    public static final Map<UUID, AtomicInteger> RUNNING = new ConcurrentHashMap<>();
    public static final AtomicInteger MAX_CONCURRENT = new AtomicInteger();
    public static final Semaphore RELEASE = new Semaphore(0);

    @ApplicationModuleListener
    void on(SpikeEvent event) {
        INVOCATIONS.computeIfAbsent(event.id(), k -> new AtomicInteger()).incrementAndGet();
        int running = RUNNING.computeIfAbsent(event.id(), k -> new AtomicInteger()).incrementAndGet();
        MAX_CONCURRENT.accumulateAndGet(running, Math::max);
        try {
            switch (BEHAVIOUR.getOrDefault(event.id(), Behaviour.SUCCEED)) {
                case FAIL -> throw new IllegalStateException("Spike listener fails for " + event.id());
                case BLOCK -> RELEASE.acquireUninterruptibly();
                case SUCCEED -> { }
            }
        } finally {
            RUNNING.get(event.id()).decrementAndGet();
        }
    }

    public static int invocations(UUID id) {
        AtomicInteger count = INVOCATIONS.get(id);
        return count == null ? 0 : count.get();
    }
}
