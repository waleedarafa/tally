/**
 * Queue and job infrastructure only, never business logic. Depends on no business module (ADR-027).
 */
@ApplicationModule(displayName = "Platform", allowedDependencies = { "shared" })
package com.tally.platform;

import org.springframework.modulith.ApplicationModule;
