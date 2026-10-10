package com.tally.offers;

/**
 * Offers and Pricing's open-host service (design.md §4, ADR-015): what a subscription owes for one
 * period, with the reason. Pure for given terms: the same subscription, period and offer version give the
 * same quote (O3).
 */
public interface Pricing {

    /**
     * Quotes the requested offer version in the account's currency.
     *
     * @throws UnknownPlanException if the offer or that version does not exist, or it has no price in that currency
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     */
    PriceQuote quote(QuoteRequest request);
}
