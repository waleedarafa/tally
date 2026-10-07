package com.tally.platform;

import java.time.Instant;

/** What a {@link QueueHandler} reports for an item. A handler that throws marks the item FAILED. */
public sealed interface Outcome {

    /** The work is finished. */
    static Outcome done() {
        return Done.INSTANCE;
    }

    /** Return the item to the queue, due again at {@code dueAt}. Not a failure (for example, a 429). */
    static Outcome retryAt(Instant dueAt) {
        return new RetryAt(dueAt);
    }

    enum Done implements Outcome { INSTANCE }

    record RetryAt(Instant dueAt) implements Outcome {
    }
}
