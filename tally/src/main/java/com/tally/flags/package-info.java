/** Feature flags (ADR-010, ADR-048, ADR-049): a billing catalogue, assigned per account or country, with changes logged, persisted as a snapshot, and removed within 30 days of full release. Public: {@code FlagsApi}. Inside: {@code application}, {@code domain}, {@code adapter.persistence}. */
@ApplicationModule(displayName = "Flags", allowedDependencies = { "shared", "platform" })
package com.tally.flags;

import org.springframework.modulith.ApplicationModule;
