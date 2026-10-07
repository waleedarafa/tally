package com.tally.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When reconciliation checks an attempt again (ADR-018): 5 minutes after it became UNKNOWN or FAILED,
 * then 1 hour after the first check, then daily. A FAILED attempt is only re-checked for 48 hours.
 */
public final class ReconciliationSchedule {

    static final Duration FIRST = Duration.ofMinutes(5);
    static final Duration SECOND = Duration.ofHours(1);
    static final Duration THEN = Duration.ofDays(1);
    static final Duration FAILED_WINDOW = Duration.ofHours(48);

    private ReconciliationSchedule() {
    }

    public static Instant first(Instant resolvedAt) {
        return resolvedAt.plus(FIRST);
    }

    /** After {@code checksDone} checks that changed nothing. */
    public static Instant after(int checksDone, Instant now) {
        return now.plus(checksDone <= 1 ? SECOND : THEN);
    }

    /** The next check of a FAILED attempt, if still inside the 48-hour window. */
    public static Optional<Instant> afterFailed(int checksDone, Instant failedAt, Instant now) {
        Instant next = after(checksDone, now);
        return next.isAfter(failedAt.plus(FAILED_WINDOW)) ? Optional.empty() : Optional.of(next);
    }
}
