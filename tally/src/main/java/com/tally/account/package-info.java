/**
 * Customer accounts: currency, time zone, payment token and standing. Only Collections sets the standing (F3).
 */
@ApplicationModule(displayName = "Account", allowedDependencies = { "shared", "platform" })
package com.tally.account;

import org.springframework.modulith.ApplicationModule;
