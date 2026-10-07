/**
 * Subscriptions: records business changes only (ADR-002).
 */
@ApplicationModule(displayName = "Subscription", allowedDependencies = { "shared", "platform", "account", "catalog" })
package com.tally.subscription;

import org.springframework.modulith.ApplicationModule;
