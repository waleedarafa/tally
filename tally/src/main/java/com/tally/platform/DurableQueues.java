package com.tally.platform;

/** Registers durable queues. A module registers each of its queues once, at startup. */
public interface DurableQueues {

    /**
     * Registers a queue and its handler. Fails fast if the table does not have the shape
     * described on {@link QueueDefinition}.
     */
    DurableQueue register(QueueDefinition definition, QueueHandler handler);

    /** As {@link #register(QueueDefinition, QueueHandler)}, claiming only while {@code gate} is open. */
    DurableQueue register(QueueDefinition definition, QueueHandler handler, ClaimGate gate);
}
