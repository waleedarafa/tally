package com.tally.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The D4 calendar edge cases (AT-3), as fast unit tests. */
class BillingCalendarTest {

    static List<LocalDate> datesFrom(String start, int count) {
        LocalDate date = LocalDate.parse(start);
        BillingCalendar calendar = BillingCalendar.startingOn(date);
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            dates.add(date);
            date = calendar.nextBillingDate(date);
        }
        return dates;
    }

    @Test
    void theThirtyFirstBillsOnTheLastDayOfShortMonthsAndReturnsToTheThirtyFirst() {
        assertThat(datesFrom("2026-01-31", 7)).containsExactly(
                LocalDate.parse("2026-01-31"),
                LocalDate.parse("2026-02-28"),
                LocalDate.parse("2026-03-31"),
                LocalDate.parse("2026-04-30"),
                LocalDate.parse("2026-05-31"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-31"));
    }

    @Test
    void aLeapYearBillsOnTheTwentyNinthOfFebruary() {
        assertThat(datesFrom("2028-01-31", 3)).containsExactly(
                LocalDate.parse("2028-01-31"), LocalDate.parse("2028-02-29"), LocalDate.parse("2028-03-31"));
        assertThat(datesFrom("2028-01-30", 3)).containsExactly(
                LocalDate.parse("2028-01-30"), LocalDate.parse("2028-02-29"), LocalDate.parse("2028-03-30"));
    }

    @Test
    void aSubscriptionFromTheTwentyNinthOfFebruaryKeepsItsAnchor() {
        assertThat(datesFrom("2028-02-29", 4)).containsExactly(LocalDate.parse("2028-02-29"),
                LocalDate.parse("2028-03-29"), LocalDate.parse("2028-04-29"), LocalDate.parse("2028-05-29"));
        assertThat(datesFrom("2028-02-29", 13).getLast()).isEqualTo(LocalDate.parse("2029-02-28"));
    }

    @Test
    void ordinaryDaysAreStable() {
        assertThat(datesFrom("2026-01-15", 13).getLast()).isEqualTo(LocalDate.parse("2027-01-15"));
    }

    @ParameterizedTest
    @CsvSource({
            // Local midnight in a zone ahead of UTC is the previous evening in UTC.
            "Asia/Dubai, 2026-02-15, 2026-02-14T20:00:00Z",
            // New York: midnight is 05:00Z in winter (EST) and 04:00Z in summer (EDT).
            "America/New_York, 2026-02-28, 2026-02-28T05:00:00Z",
            "America/New_York, 2026-03-31, 2026-03-31T04:00:00Z",
            "America/New_York, 2026-11-30, 2026-11-30T05:00:00Z",
            // Santiago skips 00:00 on 6 September 2026: billing happens at 01:00 local, the day's first instant.
            "America/Santiago, 2026-09-06, 2026-09-06T04:00:00Z",
            "UTC, 2026-01-31, 2026-01-31T00:00:00Z"
    })
    void billingHappensAtLocalMidnight(String zone, String date, String expected) {
        assertThat(BillingCalendar.billingTime(LocalDate.parse(date), ZoneId.of(zone)))
                .isEqualTo(Instant.parse(expected));
    }

    @Test
    void theAnchorIsADayOfTheMonth() {
        assertThatThrownBy(() -> new BillingCalendar(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingCalendar(32)).isInstanceOf(IllegalArgumentException.class);
    }
}
