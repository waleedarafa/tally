package com.tally.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * ADR-031: the next check of an UNKNOWN attempt is the first time after now in its schedule, counted from when it
 * became UNKNOWN: 5, 15, 30 and 45 minutes, then every hour while its key may be repeated, then every day. A late
 * check never moves the times after it.
 */
class UnknownCheckScheduleTest {

    static final Instant UNKNOWN_AT = Instant.parse("2026-01-15T10:00:00Z");
    static final Instant WINDOW_END = UNKNOWN_AT.plus(Duration.ofHours(20));

    static Duration next(Duration elapsed) {
        return Duration.between(UNKNOWN_AT,
                ReconciliationSchedule.nextForUnknown(UNKNOWN_AT, WINDOW_END, UNKNOWN_AT.plus(elapsed)));
    }

    @Test
    void fourChecksInTheFirstHourThenHourlyInsideTheKeyWindowThenDaily() {
        assertThat(next(Duration.ZERO)).isEqualTo(Duration.ofMinutes(5));
        assertThat(next(Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(15));
        assertThat(next(Duration.ofMinutes(15))).isEqualTo(Duration.ofMinutes(30));
        assertThat(next(Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(45));
        assertThat(next(Duration.ofMinutes(45))).isEqualTo(Duration.ofHours(1));
        assertThat(next(Duration.ofHours(1))).isEqualTo(Duration.ofHours(2));
        assertThat(next(Duration.ofHours(18))).isEqualTo(Duration.ofHours(19));
        assertThat(next(Duration.ofHours(19))).as("20 hours is the window's end: no repeat there")
                .isEqualTo(Duration.ofDays(1));
        assertThat(next(Duration.ofDays(1))).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void aLateCheckDoesNotMoveTheTimesAfterIt() {
        assertThat(next(Duration.ofMinutes(9))).isEqualTo(Duration.ofMinutes(15));
        assertThat(next(Duration.ofMinutes(50))).isEqualTo(Duration.ofHours(1));
        assertThat(next(Duration.ofMinutes(61))).isEqualTo(Duration.ofHours(2));
        assertThat(next(Duration.ofHours(7).plusMinutes(59))).isEqualTo(Duration.ofHours(8));
        assertThat(next(Duration.ofHours(30))).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void anAttemptThatBecameUnknownLateHasFewerHourlyChecks() {
        // Sent long before it became UNKNOWN (a stale attempt found late): the key window ends sooner.
        Instant windowEnd = UNKNOWN_AT.plus(Duration.ofHours(3).plusMinutes(30));
        assertThat(Duration.between(UNKNOWN_AT, ReconciliationSchedule.nextForUnknown(UNKNOWN_AT, windowEnd,
                UNKNOWN_AT.plus(Duration.ofHours(3))))).as("past the window: daily").isEqualTo(Duration.ofDays(1));
    }
}
