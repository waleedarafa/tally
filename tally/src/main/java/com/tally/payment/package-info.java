/**
 * Payment: accepts an amount and an opaque reference. Knows nothing about invoices (ADR-014).
 */
@ApplicationModule(displayName = "Payment", allowedDependencies = { "shared", "platform" })
package com.tally.payment;

import org.springframework.modulith.ApplicationModule;
