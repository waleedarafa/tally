package com.tally.invoicing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * The billing calendar (FR-002; brief section 1). Invoicing is its only owner.
 *
 * <p>The anchor day is the local calendar day the subscription started. In a shorter month the
 * account is billed on the month's last day, but the anchor is kept: a subscription from 31 January
 * bills on 28 (or 29) February, then on 31 March, then on 30 April. Every date is computed from the
 * anchor, never from the previous date, so a short month never drags later dates earlier.
 *
 * @param anchorDay 1 to 31
 */
public record BillingCalendar(int anchorDay) {

    public BillingCalendar {
        if (anchorDay < 1 || anchorDay > 31) {
            throw new IllegalArgumentException("Anchor day must be 1 to 31: " + anchorDay);
        }
    }

    /** The calendar of a subscription that started on {@code startDate}, a local date. */
    public static BillingCalendar startingOn(LocalDate startDate) {
        return new BillingCalendar(startDate.getDayOfMonth());
    }

    /** The billing date in a month: the anchor day, or the month's last day if shorter. */
    public LocalDate billingDateIn(YearMonth month) {
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
    }

    /** The billing date after {@code billingDate}: the end of the period that starts on it. */
    public LocalDate nextBillingDate(LocalDate billingDate) {
        return billingDateIn(YearMonth.from(billingDate).plusMonths(1));
    }

    /**
     * When billing for a date happens: 00:00 local time on that date (brief section 1). If local
     * midnight does not exist that day (a daylight-saving gap), the first valid instant of the day.
     */
    public static Instant billingTime(LocalDate billingDate, ZoneId zone) {
        return billingDate.atStartOfDay(zone).toInstant();
    }
}
