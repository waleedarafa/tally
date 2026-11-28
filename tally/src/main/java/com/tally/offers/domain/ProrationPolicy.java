package com.tally.offers.domain;

import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * O4: proration is by days. A change effective on {@code effectiveDate} of a period credits the days
 * from that date to the period end at the old price, and charges the same days at the new price. Days
 * are the days of {@code [start, end)}: a change on day {@code k} of a {@code n}-day period uses
 * {@code k - 1} days and leaves {@code n - k + 1} remaining. Rounding happens only inside
 * {@link Money#times(Ratio)}, half up (ADR-017); totals are sums, never re-rounded. A short first
 * period is prorated over the regular period's days, so the credit never exceeds what the period
 * invoice billed.
 */
public final class ProrationPolicy {

    private ProrationPolicy() {}

    /** The lines for a change effective on {@code effectiveDate}: a credit at the old price, a charge at the new. {@code regularDays} is the number of days of the regular period the old price is for, which may exceed the period's days for a short first period. */
    public static List<PriceLine> lines(Money oldPricePerPeriod, Money newPricePerPeriod, Period period,
            LocalDate effectiveDate, long regularDays) {
        Objects.requireNonNull(oldPricePerPeriod, "oldPricePerPeriod");
        Objects.requireNonNull(newPricePerPeriod, "newPricePerPeriod");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        if (!oldPricePerPeriod.currency().equals(newPricePerPeriod.currency())) {
            throw new IllegalArgumentException("Old price " + oldPricePerPeriod + " and new price "
                    + newPricePerPeriod + " differ in currency");
        }
        long start = period.start().toEpochDay();
        long end = period.end().toEpochDay();
        long change = effectiveDate.toEpochDay();
        long totalDays = end - start;
        if (change < start || change >= end) {
            throw new IllegalArgumentException("The change date " + effectiveDate + " is not in period "
                    + period.number() + " (" + period.start() + " to " + period.end() + ")");
        }
        if (regularDays < totalDays) {
            throw new IllegalArgumentException("The regular period is " + regularDays + " days, fewer than the "
                    + totalDays + " days of period " + period.number() + " (" + period.start() + " to "
                    + period.end() + ")");
        }
        long remainingDays = end - change;
        Money credit = oldPricePerPeriod.times(Ratio.of(remainingDays, regularDays));
        Money charge = newPricePerPeriod.times(Ratio.of(remainingDays, regularDays));
        return List.of(
                new PriceLine("Credit, " + remainingDays + " of " + regularDays + " days at the old price ("
                        + oldPricePerPeriod.toDecimal().toPlainString() + ")",
                        new Money(-credit.minorUnits(), charge.currency())),
                new PriceLine("Charge, " + remainingDays + " of " + regularDays + " days at the new price ("
                        + newPricePerPeriod.toDecimal().toPlainString() + ")", charge));
    }
}
