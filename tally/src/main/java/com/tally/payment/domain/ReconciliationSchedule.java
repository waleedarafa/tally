package com.tally.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When reconciliation checks an attempt again (FR-018).
 * <ul>
 *   <li>UNKNOWN (ADR-031): 5, 15, 30 and 45 minutes after it became UNKNOWN, then hourly while its key may be
 *       repeated (ADR-026 b), then daily. Every time counts from when it became UNKNOWN, never from the last check,
 *       so a late check does not delay the ones after it. Four checks fall inside D2's hour.</li>
 *   <li>DECLINED: 5 minutes after the decline, then 1 hour after the first check, then daily, for 48 hours.</li>
 * </ul>
 */
public final class ReconciliationSchedule {

    static final Duration FIRST = Duration.ofMinutes(5);
    static final Duration SECOND = Duration.ofHours(1);
    static final Duration THEN = Duration.ofDays(1);
    static final Duration FAILED_WINDOW = Duration.ofHours(48);
    static final List<Duration> UNKNOWN_FIRST_HOUR = List.of(Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofMinutes(30), Duration.ofMinutes(45));
    static final Duration HOURLY = Duration.ofHours(1);
    static final Duration DAILY = Duration.ofDays(1);

    private ReconciliationSchedule() {
    }

    public static Instant first(Instant resolvedAt) {
        return resolvedAt.plus(FIRST);
    }

    /**
     * The next check of an UNKNOWN attempt that became UNKNOWN at {@code unknownAt}, and whose key may be repeated
     * until {@code keyWindowEnd}: the first time in its schedule after {@code now}.
     */
    public static Instant nextForUnknown(Instant unknownAt, Instant keyWindowEnd, Instant now) {
        for (Duration offset : UNKNOWN_FIRST_HOUR) {
            Instant at = unknownAt.plus(offset);
            if (at.isAfter(now)) {
                return at;
            }
        }
        Instant hourly = unknownAt.plus(HOURLY.multipliedBy(periodsAfter(unknownAt, now, HOURLY)));
        if (hourly.isBefore(keyWindowEnd)) {
            return hourly;
        }
        return unknownAt.plus(DAILY.multipliedBy(periodsAfter(unknownAt, now, DAILY)));
    }

    /** The smallest n of at least 1 with {@code start + n * period} after {@code now}. */
    private static long periodsAfter(Instant start, Instant now, Duration period) {
        long elapsed = Duration.between(start, now).toMillis();
        return Math.max(1, Math.floorDiv(elapsed, period.toMillis()) + 1);
    }

    /** A DECLINED attempt, after {@code checksDone} checks that changed nothing. */
    public static Instant after(int checksDone, Instant now) {
        return now.plus(checksDone <= 1 ? SECOND : THEN);
    }

    /** The next check of a FAILED attempt, if still inside the 48-hour window. */
    public static Optional<Instant> afterFailed(int checksDone, Instant failedAt, Instant now) {
        Instant next = after(checksDone, now);
        return next.isAfter(failedAt.plus(FAILED_WINDOW)) ? Optional.empty() : Optional.of(next);
    }
}
