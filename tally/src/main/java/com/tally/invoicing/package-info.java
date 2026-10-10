/**
 * Invoicing: owns the billing calendar (FR-002) and the payment reference mapping (FR-014).
 */
@ApplicationModule(displayName = "Invoicing", allowedDependencies = { "shared", "platform", "account", "catalog", "subscription", "payment" })
package com.tally.invoicing;

import org.springframework.modulith.ApplicationModule;
