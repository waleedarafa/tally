package com.tally.offers;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What to quote for one mid-period change: a subscription on one offer version, changed to another,
 * effective on {@code effectiveDate}. The period is the one the change lands in; the effective date
 * must fall inside it. The old version gives the price the unused days are credited at; the new
 * version gives the price the remaining days are charged at (O4).
 */
public record ChangeRequest(UUID subscriptionId, UUID accountId, String oldOfferId, int oldOfferVersion,
        String newOfferId, int newOfferVersion, Period period, LocalDate effectiveDate) {

    public ChangeRequest {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(oldOfferId, "oldOfferId");
        Objects.requireNonNull(newOfferId, "newOfferId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        if (oldOfferVersion < 1) {
            throw new IllegalArgumentException("Offer versions are numbered from 1: " + oldOfferVersion);
        }
        if (newOfferVersion < 1) {
            throw new IllegalArgumentException("Offer versions are numbered from 1: " + newOfferVersion);
        }
        if (effectiveDate.isBefore(period.start()) || !effectiveDate.isBefore(period.end())) {
            throw new IllegalArgumentException("The change date " + effectiveDate + " is not in period "
                    + period.number() + " (" + period.start() + " to " + period.end() + ")");
        }
    }
}
