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

    /**
     * The "what does this change cost?" answer (O4, ADR-040): what a mid-period change costs in one
     * quote. Unused days are credited at the old price, remaining days are charged at the new price;
     * days are the days of {@code [start, end)}. The quote prices the change, not the period, and it
     * never prices the period's invoice line: a whole-period quote is {@link #quote(QuoteRequest)},
     * and an issued invoice never changes (I1).
     *
     * @throws UnknownPlanException if either offer or that version does not exist, or it has no price in that currency
     *
     * @throws IllegalArgumentException if the change date is not in the period
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     */
    PriceQuote quoteChange(ChangeRequest request);
}
