package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import spike.modulith.SpikeConfiguration;
import spike.modulith.SpikeEvent;
import spike.modulith.SpikeListener;
import spike.modulith.SpikeListener.Behaviour;

/** R3: asynchronous event listeners run on the platform's pool, which reports its health (FR-023). */
@Import(SpikeConfiguration.class)
class EventListenerExecutionIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MeterRegistry meters;

    @Test
    void listenersRunOnThePlatformPoolAndItsMetricsCountThem() {
        double completed = value("tally.events.listeners.completed");
        double failed = value("tally.events.listeners.failed");
        UUID succeeds = UUID.randomUUID();
        UUID fails = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(fails, Behaviour.FAIL);

        clock.advance(Duration.ofMinutes(1));
        publish(succeeds);
        await().atMost(Duration.ofSeconds(10)).until(() -> value("tally.events.listeners.completed") >= completed + 1);
        assertThat(SpikeListener.THREAD.get(succeeds)).startsWith("tally-events-");
        assertThat(value("tally.events.listeners.last_success_age_seconds")).isZero();

        clock.advance(Duration.ofMinutes(1));
        publish(fails);
        await().atMost(Duration.ofSeconds(10)).until(() -> value("tally.events.listeners.failed") >= failed + 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> value("tally.events.listeners.completed") >= completed + 2);
        assertThat(SpikeListener.THREAD.get(fails)).startsWith("tally-events-");
        // A failure is not a success: the age keeps growing from the last good run.
        assertThat(value("tally.events.listeners.last_success_age_seconds")).isEqualTo(60.0);

        assertThat(value("tally.events.listeners.threads")).isPositive();
        assertThat(value("tally.events.listeners.queued")).isZero();
        assertThat(meters.find("tally.events.listeners.active").gauge()).isNotNull();
    }

    @Test
    void thePlatformPoolIsTheOnlyExecutorBean() {
        assertThat(context.getBeansOfType(Executor.class)).containsOnlyKeys("eventListenerExecutor");
    }

    private void publish(UUID id) {
        tx.executeWithoutResult(t -> context.publishEvent(new SpikeEvent(id)));
    }

    private double value(String name) {
        return meters.get(name).meter().measure().iterator().next().getValue();
    }
}
