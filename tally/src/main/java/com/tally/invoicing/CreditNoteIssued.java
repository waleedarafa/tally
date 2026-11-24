package com.tally.invoicing;

import com.tally.shared.DomainEvent;
import com.tally.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** Pinned by name (FR-009): never rename or move this class; change it additively only. */
public record CreditNoteIssued(
        UUID eventId,
        Instant occurredAt,
        UUID accountId,
        UUID creditNoteId,
        UUID invoiceId,
        Money amount,
        /** The aggregate's version: the sequence number (ADR-016). Null if published before Slice 1R PR-7. */
        Long sequence) implements DomainEvent {
}
