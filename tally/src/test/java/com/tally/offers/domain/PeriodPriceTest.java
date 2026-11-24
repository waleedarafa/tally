package com.tally.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.offers.Period;
import com.tally.shared.Money;
import com.tally.subscription.domain.FirstPeriod;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * PeriodPrice must agree with FirstPeriod on the first-period goldens (ADR-043, O4, ADR-017), so a
 * period invoice is priced the same way the first period is dated.
 */
class PeriodPriceTest {

    static final Money FULL = Money.of(1200, "AED");

    @Test
    void aFullPeriodIsTheFullPrice() {
        Period period = new Period(1, LocalDate.parse("2026-04-05"), LocalDate.parse("2026-05-05"));
        assertThat(PeriodPrice.of(FULL, period, 5)).isEqualTo(Money.of(1200, "AED"));
        assertThat(PeriodPrice.of(FULL, period, 5))
                .isEqualTo(FirstPeriod.of(period.start(), 5, FULL).amount());
    }

    @Test
    void aLateStartIsPricedByDaysOfTheRegularPeriod() {
        Period period = new Period(1, LocalDate.parse("2026-04-20"), LocalDate.parse("2026-05-05"));
        assertThat(PeriodPrice.of(FULL, period, 5)).isEqualTo(Money.of(600, "AED"));
        assertThat(PeriodPrice.of(FULL, period, 5))
                .isEqualTo(FirstPeriod.of(period.start(), 5, FULL).amount());
    }

    @Test
    void aStartBeforeTheBillingDateIsFourOfThirtyOneDays() {
        Period period = new Period(1, LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-05"));
        assertThat(PeriodPrice.of(FULL, period, 5)).isEqualTo(Money.of(155, "AED"));
        assertThat(PeriodPrice.of(FULL, period, 5))
                .isEqualTo(FirstPeriod.of(period.start(), 5, FULL).amount());
    }

    @Test
    void aShortFebruaryIsThirteenOfTwentyEightDays() {
        Period period = new Period(1, LocalDate.parse("2026-02-15"), LocalDate.parse("2026-02-28"));
        assertThat(PeriodPrice.of(FULL, period, 31)).isEqualTo(Money.of(557, "AED"));
        assertThat(PeriodPrice.of(FULL, period, 31))
                .isEqualTo(FirstPeriod.of(period.start(), 31, FULL).amount());
    }
}
