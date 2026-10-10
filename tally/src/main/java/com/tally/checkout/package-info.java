/**
 * Checkout (FR-019): records the payment token, starts the subscription, then waits briefly for
 * the first payment's result. A process module: it coordinates other modules through their public
 * APIs, owns no data, and nothing depends on it (slice 1 report, R5).
 */
@ApplicationModule(displayName = "Checkout", allowedDependencies = { "account", "offers", "subscription", "invoicing", "payment" })
package com.tally.checkout;

import org.springframework.modulith.ApplicationModule;
