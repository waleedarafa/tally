/**
 * Queue, job and idempotency-key infrastructure only, never business logic. Depends on no business module
 * (FR-027, extended in Slice 1R PR-9b).
 */
@ApplicationModule(displayName = "Platform", allowedDependencies = { "shared" })
package com.tally.platform;

import org.springframework.modulith.ApplicationModule;
