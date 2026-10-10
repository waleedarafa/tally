package com.tally.platform;

/**
 * Does the work for one queue item. Called with no transaction open (FR-005), and possibly more
 * than once for the same item: after a crash, the reaper returns the item to the queue. Handlers
 * must therefore be idempotent, with a business unique constraint as the final guard (FR-017).
 */
@FunctionalInterface
public interface QueueHandler {

    Outcome handle(QueueItem item) throws Exception;
}
