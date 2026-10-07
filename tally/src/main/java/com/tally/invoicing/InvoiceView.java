package com.tally.invoicing;

import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** {@code periodEnd} is exclusive: the next period starts on it. */
public record InvoiceView(UUID id, UUID accountId, UUID subscriptionId, InvoiceStatus status, Money amount,
        LocalDate periodStart, LocalDate periodEnd, String paymentReference, Instant issuedAt) {
}
