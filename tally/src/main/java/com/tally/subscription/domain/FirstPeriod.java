package com.tally.subscription.domain;

import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * The first billing period of a subscription (ADR-043, O4). It is the period from the start date to
 * the first billing date after it: either the billing date of the start month (a short period when
 * the start date is before that billing date) or the next month's billing date (a full period when
 * the start date is on that billing date; a short period when it is after it). Priced by days,
 * half up inside {@link Money#times(Ratio)}.
 *
 * @param start the start date of the period (local)
 * @param end the end date of the period (exclusive)
 * @param amount the full-period price times the day ratio, in that price's currency
 */
public record FirstPeriod(LocalDate start, LocalDate end, Money amount) {

    public FirstPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(amount, "amount");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Period end " + end + " must be after start " + start);
        }
    }

    /** The exclusive end of the first period that starts on {@code startDate} against {@code billingDay}. */
    public static LocalDate endDate(LocalDate startDate, int billingDay) {
        Objects.requireNonNull(startDate, "startDate");
        BillingCalendar calendar = new BillingCalendar(billingDay);
        LocalDate thisMonth = calendar.billingDateIn(YearMonth.from(startDate));
        return startDate.isBefore(thisMonth) ? thisMonth : calendar.nextBillingDate(thisMonth);
    }

    /**
     * The first period of a subscription starting on {@code startDate}, on an account with the given
     * billing day (1 to 31), at the given full-period price.
     */
    public static FirstPeriod of(LocalDate startDate, int billingDay, Money fullPeriodPrice) {
        Objects.requireNonNull(fullPeriodPrice, "fullPeriodPrice");
        LocalDate end = endDate(startDate, billingDay);
        BillingCalendar calendar = new BillingCalendar(billingDay);
        LocalDate previousBillingDate = calendar.billingDateIn(YearMonth.from(end).minusMonths(1));
        long firstPeriodDays = end.toEpochDay() - startDate.toEpochDay();
        long fullPeriodDays = end.toEpochDay() - previousBillingDate.toEpochDay();
        Money amount = fullPeriodPrice.times(Ratio.of(firstPeriodDays, fullPeriodDays));
        return new FirstPeriod(startDate, end, amount);
    }
}
