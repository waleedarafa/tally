package com.tally.offers;

import java.util.Objects;
import java.util.UUID;

/**
 * What to quote: a subscription's period, for an account, on the offer version the subscription is on.
 * The version is required: a subscription keeps the version it signed up to, and a new version applies
 * to new sign-ups only (decision A; O3).
 */
public record QuoteRequest(UUID subscriptionId, UUID accountId, String offerId, int offerVersion, Period period) {

    public QuoteRequest {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(period, "period");
        if (offerVersion < 1) {
            throw new IllegalArgumentException("Offer versions are numbered from 1: " + offerVersion);
        }
    }
}
