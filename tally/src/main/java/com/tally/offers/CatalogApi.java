package com.tally.offers;

import java.util.UUID;

/** Offers and Pricing: an open-host service with a published language, {@link PriceQuote} (FR-014). */
public interface CatalogApi {

    /**
     * The price of a plan for one period, in the account's currency.
     *
     * @throws UnknownPlanException if the plan does not exist or has no price in that currency
     * @throws com.tally.account.UnknownAccountException if there is no such account
     */
    PriceQuote quote(UUID accountId, String planId, BillingPeriod period);
}
