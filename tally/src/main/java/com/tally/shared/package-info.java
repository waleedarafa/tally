/**
 * Shared kernel: {@code Money}, {@code Ratio} and the event marker {@code DomainEvent}, and nothing else
 * (R5, ADR-017). The list is pinned by F4, {@code architecture.SharedKernelTest}.
 */
@ApplicationModule(displayName = "Shared kernel", allowedDependencies = {})
package com.tally.shared;

import org.springframework.modulith.ApplicationModule;
