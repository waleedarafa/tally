/**
 * Subscriptions: records business changes only (FR-002).
 */
@ApplicationModule(displayName = "Subscription", allowedDependencies = { "shared", "platform", "account", "offers" })
package com.tally.subscription;

import org.springframework.modulith.ApplicationModule;
