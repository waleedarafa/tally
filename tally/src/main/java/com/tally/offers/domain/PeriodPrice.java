package com.tally.offers.domain;

import com.tally.offers.Period;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.time.YearMonth;
import java.util.Objects;

/**
 * The amount owed for one period, by days of the regular period that ends on the same date (O4,
 * ADR-043, ADR-017). A period that starts on the billing day is the full price. Rounding happens
 * only inside {@link Money#times(Ratio)}. The billing-date rule matches Subscription's calendar:
 * the billing day, or the month's last day if shorter.
 */
public final class PeriodPrice {

    private PeriodPrice() {}

    /** {@code pricePerPeriod} times the day ratio of {@code period} against {@code billingDay}. */
    public static Money of(Money pricePerPeriod, Period period, int billingDay) {
        Objects.requireNonNull(pricePerPeriod, "pricePerPeriod");
        Objects.requireNonNull(period, "period");
        if (billingDay < 1 || billingDay > 31) {
            throw new IllegalArgumentException("Billing day must be 1 to 31: " + billingDay);
        }
        YearMonth previousMonth = YearMonth.from(period.end()).minusMonths(1);
        long fullPeriodDays = period.end().toEpochDay()
                - previousMonth.atDay(Math.min(billingDay, previousMonth.lengthOfMonth())).toEpochDay();
        long periodDays = period.end().toEpochDay() - period.start().toEpochDay();
        return pricePerPeriod.times(Ratio.of(periodDays, fullPeriodDays));
    }
}
