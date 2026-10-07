package com.tally.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TestClockTest {

    @Test
    void startsAtAFixedInstantAndAdvances() {
        TestClock clock = new TestClock();

        assertThat(clock.instant()).isEqualTo(TestClock.DEFAULT_START);
        clock.advance(Duration.ofDays(31));
        assertThat(clock.instant()).isEqualTo(TestClock.DEFAULT_START.plus(Duration.ofDays(31)));
    }

    @Test
    void zonedViewsShareTheSameTime() {
        TestClock clock = new TestClock();
        var dubai = clock.withZone(ZoneId.of("Asia/Dubai"));

        clock.set(Instant.parse("2026-02-28T20:00:00Z"));

        assertThat(dubai.instant()).isEqualTo(Instant.parse("2026-02-28T20:00:00Z"));
        assertThat(LocalDate.now(dubai)).isEqualTo(LocalDate.parse("2026-03-01"));
    }
}
