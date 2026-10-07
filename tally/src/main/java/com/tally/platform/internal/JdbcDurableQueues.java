package com.tally.platform.internal;

import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Registry of every queue on this instance. Pollers and health metrics read from it. */
@Component
class JdbcDurableQueues implements DurableQueues {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PlatformProperties properties;
    private final InstanceId instance = InstanceId.fromEnvironment();
    private final List<JdbcDurableQueue> queues = new CopyOnWriteArrayList<>();
    private final List<Consumer<JdbcDurableQueue>> listeners = new CopyOnWriteArrayList<>();

    JdbcDurableQueues(JdbcTemplate jdbc, Clock clock, PlatformProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public DurableQueue register(QueueDefinition definition, QueueHandler handler) {
        return register(definition, JdbcDurableQueue.processorFor(handler));
    }

    synchronized JdbcDurableQueue register(QueueDefinition definition, JdbcDurableQueue.Processor processor) {
        if (queues.stream().anyMatch(q -> q.name().equals(definition.name()))) {
            throw new IllegalStateException("Queue " + definition.name() + " is already registered");
        }
        QueueTableCheck.verify(jdbc, definition.table());
        JdbcDurableQueue queue = create(definition, processor, instance.value());
        queues.add(queue);
        listeners.forEach(l -> l.accept(queue));
        return queue;
    }

    /** Calls {@code listener} for every registered queue, now and when registered later. */
    synchronized void onRegistered(Consumer<JdbcDurableQueue> listener) {
        listeners.add(listener);
        queues.forEach(listener);
    }

    /** A queue object not added to the registry. Tests use it to act as a second instance. */
    JdbcDurableQueue create(QueueDefinition definition, QueueHandler handler, String instanceName) {
        return create(definition, JdbcDurableQueue.processorFor(handler), instanceName);
    }

    JdbcDurableQueue create(QueueDefinition definition, JdbcDurableQueue.Processor processor, String instanceName) {
        return new JdbcDurableQueue(definition, processor, jdbc, clock, instanceName, properties.claimTimeout());
    }

    List<JdbcDurableQueue> queues() {
        return List.copyOf(queues);
    }
}
