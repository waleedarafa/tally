package com.tally.invoicing;

import com.tally.shared.DomainEvent;
import com.tally.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment for an invoice was declined: the invoice is unpaid (design.md §4). Collections opens its case
 * on it. Published by Invoicing on a declined PaymentFailed, so Collections never depends on Payment.
 * Pinned by name (FR-009): never rename or move this class; change it additively only (CLAUDE.md rule 7).
 */
public record InvoicePaymentDeclined(
        UUID eventId,
        Instant occurredAt,
        UUID accountId,
        UUID invoiceId,
        String paymentReference,
        Money amount,
        /** The invoice's version: the sequence number (ADR-016). */
        Long sequence) implements DomainEvent {
}
