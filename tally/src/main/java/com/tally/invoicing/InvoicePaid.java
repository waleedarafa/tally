package com.tally.invoicing;

import com.tally.shared.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Pinned by name (ADR-009): never rename or move this class; change it additively only. */
public record InvoicePaid(
        UUID eventId,
        Instant occurredAt,
        UUID accountId,
        UUID invoiceId,
        String paymentReference) implements DomainEvent {
}
