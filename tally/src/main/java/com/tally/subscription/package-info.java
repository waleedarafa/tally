/**
 * Subscriptions, their periods and the billing calendar (Design Record §12): renewals run on Subscription's
 * own queue and publish {@code SubscriptionChanged}; {@code renew(n)} changes nothing for a period already
 * renewed.
 */
@ApplicationModule(displayName = "Subscription", allowedDependencies = { "shared", "platform", "account", "offers" })
package com.tally.subscription;

import org.springframework.modulith.ApplicationModule;
