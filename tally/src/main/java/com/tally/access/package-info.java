/**
 * Access: answers whether an account has access. It reacts to events and answers from its own tables; it
 * never calls a billing API (design.md §3).
 */
@ApplicationModule(displayName = "Access", allowedDependencies = { "shared", "platform", "subscription", "payment" })
package com.tally.access;

import org.springframework.modulith.ApplicationModule;
