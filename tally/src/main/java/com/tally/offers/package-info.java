/**
 * Offers and Pricing, the core (design.md §1; ADR-015). Public: {@code Pricing}, {@code PriceQuote},
 * {@code ChangeRequest} and {@code Offers.currentVersion}, nothing else (R4). Inside: the {@code Offer}
 * and {@code OfferKind} aggregates, one {@code PricingRule} per kind of offer found by a registry, and
 * a start-up check that every approved kind has a rule. A mid-period change is priced by a pure
 * {@code ProrationPolicy} (O4: unused days credited at the old price, remaining days charged at the new).
 */
@ApplicationModule(displayName = "Offers and Pricing", allowedDependencies = { "shared", "platform", "account", "flags" })
package com.tally.offers;

import org.springframework.modulith.ApplicationModule;
