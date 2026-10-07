/**
 * Invoicing: owns the billing calendar (ADR-002) and the payment reference mapping (ADR-014).
 */
@ApplicationModule(displayName = "Invoicing", allowedDependencies = { "shared", "platform", "account", "catalog", "subscription", "payment" })
package com.tally.invoicing;

import org.springframework.modulith.ApplicationModule;
