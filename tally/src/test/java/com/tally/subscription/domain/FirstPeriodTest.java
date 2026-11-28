package com.tally.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.shared.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The first-period goldens (ADR-043, O4, ADR-017): dates and amounts asserted exactly. */
class FirstPeriodTest {

    static final Money FULL_PRICE = Money.of(1200, "AED");

    @Test
    void aStartOnTheBillingDayIsAFullPeriod() {
        FirstPeriod first = FirstPeriod.of(LocalDate.parse("2026-04-05"), 5, FULL_PRICE);

        assertThat(first.start()).isEqualTo(LocalDate.parse("2026-04-05"));
        assertThat(first.end()).isEqualTo(LocalDate.parse("2026-05-05"));
        // [2026-04-05, 2026-05-05) is 30 days; the regular period ending 2026-05-05 is [2026-04-05, 2026-05-05), 30 days.
        assertThat(first.amount()).isEqualTo(Money.of(1200, "AED")); // 1200 * 30/30
    }

    @Test
    void aLateStartInTheMonthIsAShorterFirstPeriod() {
        FirstPeriod first = FirstPeriod.of(LocalDate.parse("2026-04-20"), 5, FULL_PRICE);

        assertThat(first.start()).isEqualTo(LocalDate.parse("2026-04-20"));
        assertThat(first.end()).isEqualTo(LocalDate.parse("2026-05-05"));
        // [2026-04-20, 2026-05-05) is 15 days; the regular period ending 2026-05-05 is [2026-04-05, 2026-05-05), 30 days.
        assertThat(first.amount()).isEqualTo(Money.of(600, "AED")); // 1200 * 15/30
    }

    @Test
    void aStartBeforeTheBillingDateIsAShortFirstPeriodEndingOnIt() {
        FirstPeriod first = FirstPeriod.of(LocalDate.parse("2026-04-01"), 5, FULL_PRICE);

        assertThat(first.start()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(first.end()).isEqualTo(LocalDate.parse("2026-04-05"));
        // [2026-04-01, 2026-04-05) is 4 days; the regular period ending 2026-04-05 is [2026-03-05, 2026-04-05), 31 days.
        assertThat(first.amount()).isEqualTo(Money.of(155, "AED")); // 1200 * 4/31 half up = 155
    }

    @Test
    void aStartBeforeAShortMonthBillingDateIsAShortFirstPeriodEndingOnIt() {
        FirstPeriod first = FirstPeriod.of(LocalDate.parse("2026-02-15"), 31, FULL_PRICE);

        assertThat(first.start()).isEqualTo(LocalDate.parse("2026-02-15"));
        assertThat(first.end()).isEqualTo(LocalDate.parse("2026-02-28"));
        // [2026-02-15, 2026-02-28) is 13 days; the regular period ending 2026-02-28 is [2026-01-31, 2026-02-28), 28 days.
        assertThat(first.amount()).isEqualTo(Money.of(557, "AED")); // 1200 * 13/28 half up = 557
    }

    @Test
    void aPeriodThatDoesNotEndAfterStartIsRejected() {
        assertThatThrownBy(() -> new FirstPeriod(LocalDate.parse("2026-04-05"),
                LocalDate.parse("2026-04-05"), FULL_PRICE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FirstPeriod(LocalDate.parse("2026-04-05"),
                LocalDate.parse("2026-04-01"), FULL_PRICE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullStartEndAndAmountAreRejected() {
        assertThatThrownBy(() -> new FirstPeriod(null, LocalDate.parse("2026-05-05"), FULL_PRICE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FirstPeriod(LocalDate.parse("2026-04-05"), null, FULL_PRICE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FirstPeriod(LocalDate.parse("2026-04-05"),
                LocalDate.parse("2026-05-05"), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anOutOfRangeBillingDayIsRejectedByTheCalendar() {
        assertThatThrownBy(() -> FirstPeriod.of(LocalDate.parse("2026-04-05"), 0, FULL_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
