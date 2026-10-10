/**
 * Offers and Pricing, the core (design.md §1; ADR-015). Public: {@code Pricing}, {@code PriceQuote} and
 * {@code Offers.isOffered}, nothing else (R4). Inside: the {@code Offer} and {@code OfferKind} aggregates,
 * one {@code PricingRule} per kind of offer found by a registry, and a start-up check that every approved
 * kind has a rule.
 */
@ApplicationModule(displayName = "Offers and Pricing", allowedDependencies = { "shared", "platform", "account" })
package com.tally.offers;

import org.springframework.modulith.ApplicationModule;
