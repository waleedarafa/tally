/**
 * Feature flags (ADR-010, ADR-048): a billing catalogue, assigned per account or country,
 * with changes logged, and removed within 30 days of full release. Public types will sit
 * in this package when a port is added; the domain is in {@code domain}.
 */
@ApplicationModule(displayName = "Flags", allowedDependencies = { "shared", "platform" })
package com.tally.flags;

import org.springframework.modulith.ApplicationModule;
