package com.tally.offers;

import java.util.Objects;
import java.util.UUID;

/** What to quote: a subscription's period, for an account, on an offer. */
public record QuoteRequest(UUID subscriptionId, UUID accountId, String offerId, Period period) {

    public QuoteRequest {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(period, "period");
    }
}
