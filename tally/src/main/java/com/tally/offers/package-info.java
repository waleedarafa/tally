/**
 * Offers and Pricing, the core (design.md §1): the first run's catalog, renamed in Slice 1R PR-3.
 * Open-host service publishing {@code PriceQuote}. PR-4 replaces {@code CatalogApi} with {@code Pricing}.
 */
@ApplicationModule(displayName = "Offers and Pricing", allowedDependencies = { "shared", "platform", "account" })
package com.tally.offers;

import org.springframework.modulith.ApplicationModule;
