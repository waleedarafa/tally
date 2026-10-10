package com.tally.subscription;

import com.tally.shared.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A subscription changed. Pinned by name (FR-009): never rename or move this class; change it
 * additively only (ADR-016). {@code version} is the aggregate's version: the event's sequence number.
 *
 * <p>Since Slice 1R PR-5 it also carries what Invoicing needs to bill the period (ADR-016: events
 * carry what the listener needs): the offer version the subscription is on, and the current period's
 * number, start and end (local dates, end exclusive). They are null in an event an older release
 * published, which had no periods.
 */
public record SubscriptionChanged(
        UUID eventId,
        Instant occurredAt,
        UUID accountId,
        UUID subscriptionId,
        String planId,
        int version,
        SubscriptionChangeType type,
        Integer offerVersion,
        Integer periodNumber,
        LocalDate periodStart,
        LocalDate periodEnd) implements DomainEvent {

    /** The event as the first run published it, with no period. */
    public SubscriptionChanged(UUID eventId, Instant occurredAt, UUID accountId, UUID subscriptionId, String planId,
            int version, SubscriptionChangeType type) {
        this(eventId, occurredAt, accountId, subscriptionId, planId, version, type, null, null, null, null);
    }

    /** {@code version} has carried the aggregate's version since the first run: it is the sequence. */
    @Override
    public Long sequence() {
        return (long) version;
    }
}
