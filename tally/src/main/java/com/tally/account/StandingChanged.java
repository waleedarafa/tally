package com.tally.account;

import com.tally.shared.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An account's standing changed (design.md §4, ADR-033). Published by {@link AccountStanding#set} in its own
 * transaction, only when the standing changes. Pinned by name (FR-009): never rename or move this class; change it
 * additively only (ADR-016). No cause text: the cause stays in Account (ADR-006, the access feed carries no free
 * text).
 *
 * @param sequence the account's version after the change (ADR-016)
 */
public record StandingChanged(
        UUID eventId,
        Instant occurredAt,
        UUID accountId,
        Long sequence,
        Standing standing) implements DomainEvent {
}
