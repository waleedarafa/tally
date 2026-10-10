/**
 * Catalog and Pricing, the core. Open-host service publishing {@code PriceQuote} (FR-014).
 */
@ApplicationModule(displayName = "Catalog and Pricing", allowedDependencies = { "shared", "platform", "account" })
package com.tally.catalog;

import org.springframework.modulith.ApplicationModule;
