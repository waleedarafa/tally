package com.tally.platform;

/** Registers durable queues. A module registers each of its queues once, at startup. */
public interface DurableQueues {

    /**
     * Registers a queue and its handler. Fails fast if the table does not have the shape
     * described on {@link QueueDefinition}.
     */
    DurableQueue register(QueueDefinition definition, QueueHandler handler);
}
