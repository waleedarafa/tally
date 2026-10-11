/**
 * Collections (design.md §1, §5; ADR-005): a case per account when a payment is declined, retries on a
 * timetable, and the case closed when the invoice is paid. Built in closed layers (Design Record §10 C,
 * F10): {@code application} → {@code domain}. Slice 1R builds OPEN, the day-3 retry and close only.
 */
@ApplicationModule(displayName = "Collections",
        allowedDependencies = { "shared", "platform", "account", "subscription", "invoicing" })
package com.tally.collections;

import org.springframework.modulith.ApplicationModule;
