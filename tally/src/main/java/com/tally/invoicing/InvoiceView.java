package com.tally.invoicing;

import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code periodEnd} is exclusive: the next period starts on it. {@code billedFor} is when the invoice
 * was due to be billed; {@code issuedAt} is when it was issued, at or after it (D6 lateness).
 * {@code billedFor} is null only for an invoice issued by the previous release during an upgrade.
 */
public record InvoiceView(UUID id, UUID accountId, UUID subscriptionId, InvoiceStatus status, Money amount,
        LocalDate periodStart, LocalDate periodEnd, String paymentReference, Instant issuedAt, Instant billedFor) {
}
