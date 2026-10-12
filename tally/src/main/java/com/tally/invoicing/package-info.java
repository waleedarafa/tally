/**
 * Invoicing: one invoice per subscription period (I2), with the PriceQuote's lines; the payment reference
 * mapping (FR-014); the invoiced-versus-collected check. The billing calendar is Subscription's since Slice 1R
 * PR-5 (Design Record §12).
 */
@ApplicationModule(displayName = "Invoicing", allowedDependencies = { "shared", "platform", "account", "offers", "subscription", "payment" })
package com.tally.invoicing;

import org.springframework.modulith.ApplicationModule;
