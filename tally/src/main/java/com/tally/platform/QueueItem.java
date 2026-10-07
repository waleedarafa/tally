package com.tally.platform;

import java.time.Instant;

/** A claimed row, handed to a {@link QueueHandler}. {@code attempts} counts claims, this one included. */
public record QueueItem(long id, String key, Instant dueAt, String payload, int attempts) {
}
