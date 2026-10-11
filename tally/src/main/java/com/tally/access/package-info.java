/**
 * Access: answers whether an account has access. It reacts to events and answers from its own tables; it
 * never calls a billing API (design.md §3). Its dependency on {@code subscription} and {@code account} is
 * TEMPORARY until Slice 2, and limited to their events (F12AccessReactsOnlyToEventsTest).
 */
@ApplicationModule(displayName = "Access", allowedDependencies = { "shared", "platform", "subscription", "account" })
package com.tally.access;

import org.springframework.modulith.ApplicationModule;
