package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.platform.TestClock;
import com.tally.support.TallyInstances;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.transaction.support.TransactionTemplate;
import spike.modulith.SpikeConfiguration;
import spike.modulith.SpikeEvent;
import spike.modulith.SpikeListener;
import spike.modulith.SpikeListener.Behaviour;

/**
 * Spike A (brief section 9, ADR-022): what does Spring Modulith 2.1.1 really do with event
 * publications? Each experiment starts and stops real application contexts on the shared test
 * database, so "crash" and "restart" are separate application instances. Prints its evidence with
 * the prefix SPIKE-A, and asserts the behaviour observed, so it stays a permanent test (ADR-022's
 * compliance): a Modulith upgrade that changes it fails the build. Findings: docs/briefs/slice-1-spikes.md.
 */
class SpikeAEventPublicationIT {

    final TallyInstances instances = new TallyInstances();
    final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    /** Permits released to unblock an earlier test's listeners must not let this test's listeners through. */
    @BeforeEach
    void blockingListenersBlock() {
        SpikeListener.RELEASE.drainPermits();
        SpikeListener.MAX_CONCURRENT.set(0);
    }

    @AfterEach
    void stopAll() {
        SpikeListener.RELEASE.release(100);
        instances.close();
        contexts.clear();
    }

    ConfigurableApplicationContext start(String name, String... properties) {
        ConfigurableApplicationContext context = instances.start(name, SpikeConfiguration.class, properties);
        contexts.add(context);
        return context;
    }

    static void publish(ConfigurableApplicationContext context, UUID id) {
        context.getBean(TransactionTemplate.class).executeWithoutResult(
                tx -> context.publishEvent(new SpikeEvent(id)));
    }

    static List<Map<String, Object>> rows(ConfigurableApplicationContext context, UUID id) {
        return context.getBean(JdbcTemplate.class).queryForList(
                "SELECT status, completion_date IS NOT NULL AS completed, completion_attempts, "
                        + "last_resubmission_date IS NOT NULL AS resubmitted FROM event_publication "
                        + "WHERE serialized_event LIKE ?", "%" + id + "%");
    }

    static String status(ConfigurableApplicationContext context, UUID id) {
        return (String) rows(context, id).getFirst().get("status");
    }

    static void evidence(String experiment, String step, ConfigurableApplicationContext context, UUID id) {
        System.out.printf("SPIKE-A %s | %s | invocations=%d | rows=%s%n", experiment, step,
                SpikeListener.invocations(id), rows(context, id));
    }

    static void advance(ConfigurableApplicationContext context, Duration by) {
        context.getBean(TestClock.class).advance(by);
    }

    static IncompleteEventPublications incomplete(ConfigurableApplicationContext context) {
        return context.getBean(IncompleteEventPublications.class);
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A1: a crash after commit, during one listener and before another, then a restart. */
    void crashThenRestart(String experiment, boolean republishOnRestart) {
        UUID during = UUID.randomUUID();
        UUID before = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(during, Behaviour.BLOCK);
        // One async thread: the second listener cannot start while the first blocks.
        ConfigurableApplicationContext a = start("a1-instance-a",
                "tally.events.listener-threads=1");
        publish(a, during);
        await().atMost(Duration.ofSeconds(10)).until(() -> SpikeListener.invocations(during) == 1);
        publish(a, before);
        pause(500);
        evidence(experiment, "committed; listener running (during) / not started (before)", a, during);
        evidence(experiment, "committed; listener running (during) / not started (before)", a, before);
        assertThat(status(a, during)).isEqualTo("PROCESSING");
        assertThat(status(a, before)).isEqualTo("PUBLISHED");

        // The "crash": instance A goes away without the listeners finishing.
        a.close();
        contexts.remove(a);
        evidence(experiment, "after instance A stopped", a.isActive() ? a : start("probe"), during);
        SpikeListener.BEHAVIOUR.put(during, Behaviour.SUCCEED);

        ConfigurableApplicationContext b = start("a1-instance-b",
                "spring.modulith.events.republish-outstanding-events-on-restart=" + republishOnRestart);
        pause(3000);
        evidence(experiment, "instance B restarted (republish-on-restart=" + republishOnRestart + "), 3 s later", b, during);
        evidence(experiment, "instance B restarted (republish-on-restart=" + republishOnRestart + "), 3 s later", b, before);
        if (republishOnRestart) {
            // Both are re-run at startup, including the one another instance could still be running.
            assertThat(SpikeListener.invocations(during)).isEqualTo(2);
            assertThat(SpikeListener.invocations(before)).isEqualTo(1);
            assertThat(status(b, during)).isEqualTo("COMPLETED");
            assertThat(status(b, before)).isEqualTo("COMPLETED");
        } else {
            // Nothing happens on restart.
            assertThat(SpikeListener.invocations(during)).isEqualTo(1);
            assertThat(SpikeListener.invocations(before)).isZero();
            assertThat(status(b, during)).isEqualTo("PROCESSING");
            assertThat(status(b, before)).isEqualTo("PUBLISHED");
        }

        advance(b, Duration.ofMinutes(5));
        incomplete(b).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));
        pause(3000);
        evidence(experiment, "after resubmitIncompletePublicationsOlderThan(1 min), clock +5 min", b, during);
        evidence(experiment, "after resubmitIncompletePublicationsOlderThan(1 min), clock +5 min", b, before);
        // Resubmission of older publications recovers both, whatever their status.
        assertThat(status(b, during)).isEqualTo("COMPLETED");
        assertThat(status(b, before)).isEqualTo("COMPLETED");
        assertThat(SpikeListener.invocations(during)).isEqualTo(2);
        assertThat(SpikeListener.invocations(before)).isEqualTo(1);
    }

    @Test
    void a1CrashThenRestartWithoutRepublish() {
        crashThenRestart("A1-no-republish", false);
    }

    @Test
    void a1CrashThenRestartWithRepublish() {
        crashThenRestart("A1-republish", true);
    }

    /** A2: a listener throws. */
    @Test
    void a2ListenerFails() {
        ConfigurableApplicationContext a = start("a2");
        UUID olderThan = UUID.randomUUID();
        UUID options = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(olderThan, Behaviour.FAIL);
        SpikeListener.BEHAVIOUR.put(options, Behaviour.FAIL);
        publish(a, olderThan);
        publish(a, options);
        pause(2000);
        evidence("A2", "listener threw", a, olderThan);
        evidence("A2", "listener threw", a, options);
        assertThat(rows(a, olderThan).getFirst()).containsEntry("status", "FAILED").containsEntry("completed", false);

        SpikeListener.BEHAVIOUR.put(olderThan, Behaviour.SUCCEED);
        SpikeListener.BEHAVIOUR.put(options, Behaviour.SUCCEED);
        incomplete(a).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));
        pause(2000);
        evidence("A2", "resubmitIncompletePublicationsOlderThan(1 min), clock not moved", a, olderThan);
        // Age is measured on the injected clock.
        assertThat(status(a, olderThan)).isEqualTo("FAILED");

        advance(a, Duration.ofMinutes(5));
        incomplete(a).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));
        pause(2000);
        evidence("A2", "resubmitIncompletePublicationsOlderThan(1 min), clock +5 min", a, olderThan);
        assertThat(status(a, olderThan)).isEqualTo("COMPLETED");
        assertThat(SpikeListener.invocations(olderThan)).isEqualTo(2);
    }

    /** A2b: the other resubmission API, ResubmissionOptions, on a failed and on a crash-left publication. */
    @Test
    void a2bResubmissionOptions() {
        UUID failed = UUID.randomUUID();
        UUID crashed = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(failed, Behaviour.FAIL);
        SpikeListener.BEHAVIOUR.put(crashed, Behaviour.BLOCK);
        ConfigurableApplicationContext a = start("a2b-instance-a");
        publish(a, failed);
        publish(a, crashed);
        await().atMost(Duration.ofSeconds(10)).until(() -> SpikeListener.invocations(crashed) == 1);
        pause(1000);
        a.close();
        contexts.remove(a);
        SpikeListener.BEHAVIOUR.put(failed, Behaviour.SUCCEED);
        SpikeListener.BEHAVIOUR.put(crashed, Behaviour.SUCCEED);

        ConfigurableApplicationContext b = start("a2b-instance-b");
        evidence("A2b", "before: listener threw", b, failed);
        evidence("A2b", "before: crashed while running", b, crashed);
        advance(b, Duration.ofMinutes(5));
        incomplete(b).resubmitIncompletePublications(ResubmissionOptions.defaults().withMinAge(Duration.ofMinutes(1)));
        pause(3000);
        evidence("A2b", "after resubmitIncompletePublications(ResubmissionOptions, minAge 1 min), clock +5 min", b, failed);
        evidence("A2b", "after resubmitIncompletePublications(ResubmissionOptions, minAge 1 min), clock +5 min", b, crashed);
        // ResubmissionOptions resubmits FAILED publications only: a crash-left one is never picked up.
        assertThat(status(b, failed)).isEqualTo("COMPLETED");
        assertThat(status(b, crashed)).isEqualTo("PROCESSING");
        assertThat(SpikeListener.invocations(crashed)).isEqualTo(1);
    }

    /** A3: two instances resubmit the same failed publications at the same moment. */
    @Test
    void a3TwoInstancesResubmitTogether() throws Exception {
        ConfigurableApplicationContext a = start("a3-instance-a");
        ConfigurableApplicationContext b = start("a3-instance-b");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            SpikeListener.BEHAVIOUR.put(id, Behaviour.FAIL);
            publish(a, id);
            ids.add(id);
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> ids.stream().allMatch(id -> SpikeListener.invocations(id) == 1));
        pause(1000);
        ids.forEach(id -> SpikeListener.BEHAVIOUR.put(id, Behaviour.SUCCEED));
        advance(a, Duration.ofMinutes(5));
        advance(b, Duration.ofMinutes(5));

        CyclicBarrier together = new CyclicBarrier(2);
        List<Thread> threads = new ArrayList<>();
        for (ConfigurableApplicationContext instance : List.of(a, b)) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    together.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                incomplete(instance).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        pause(3000);
        long ranTwice = ids.stream().filter(id -> SpikeListener.invocations(id) > 2).count();
        System.out.printf("SPIKE-A A3 | two instances resubmit 20 failed publications together | "
                + "publications re-run more than once: %d of 20 | invocations per publication: %s%n",
                ranTwice, ids.stream().map(SpikeListener::invocations).toList());
        evidence("A3", "one example after concurrent resubmission", a, ids.getFirst());
        // Every publication completes; how many run twice depends on timing (0 to 20 seen).
        assertThat(ids).allSatisfy(id -> {
            assertThat(status(a, id)).isEqualTo("COMPLETED");
            assertThat(SpikeListener.invocations(id)).isBetween(2, 3);
        });
    }

    /** A3b: one instance's listener is still running when another instance resubmits. */
    @Test
    void a3bResubmitWhileStillRunning() {
        ConfigurableApplicationContext a = start("a3b-instance-a");
        ConfigurableApplicationContext b = start("a3b-instance-b");
        UUID id = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(id, Behaviour.BLOCK);
        publish(a, id);
        await().atMost(Duration.ofSeconds(10)).until(() -> SpikeListener.invocations(id) == 1);
        evidence("A3b", "instance A's listener running", a, id);

        advance(b, Duration.ofMinutes(5));
        int before = SpikeListener.MAX_CONCURRENT.get();
        SpikeListener.BEHAVIOUR.put(id, Behaviour.SUCCEED);
        incomplete(b).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1));
        pause(3000);
        System.out.printf("SPIKE-A A3b | B resubmitted while A's listener still runs | invocations=%d | "
                + "max concurrent runs of one publication=%d (was %d)%n",
                SpikeListener.invocations(id), SpikeListener.MAX_CONCURRENT.get(), before);
        evidence("A3b", "after B's resubmission, A still blocked", b, id);
        // An incomplete publication older than the threshold is resubmitted even while it is running.
        assertThat(SpikeListener.invocations(id)).isEqualTo(2);
        assertThat(SpikeListener.MAX_CONCURRENT.get()).isEqualTo(2);
    }
}
