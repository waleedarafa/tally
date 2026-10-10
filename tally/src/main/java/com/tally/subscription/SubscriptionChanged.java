package com.tally.subscription;

import com.tally.shared.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A subscription changed. Thin: identifiers, version and type only (brief section 2).
 * Pinned by name (FR-009): never rename or move this class; change it additively only.
 */
public record SubscriptionChanged(
        UUID eventId,
        Instant occurredAt,
        UUID accountId,
        UUID subscriptionId,
        String planId,
        int version,
        SubscriptionChangeType type) implements DomainEvent {
}
