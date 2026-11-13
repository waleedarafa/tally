/**
 * The access feed (ADR-033, design.md §3 and §4): billing's current access facts of each account, built from the
 * events of {@code subscription} and {@code account}, never from their APIs (F12). It keeps one row per account,
 * with a sequence that goes up by one only when the facts change, and holds no access rule: Access decides who may
 * watch. Built in closed layers: {@code application} → {@code domain}, with persistence behind a domain port.
 */
@ApplicationModule(displayName = "Access feed", allowedDependencies = { "shared", "platform", "subscription", "account" })
package com.tally.accessfeed;

import org.springframework.modulith.ApplicationModule;
