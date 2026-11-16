package com.tally.collections;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A Collections case, for reading. {@code closedAt} is null while it is open. {@code invoiceId} is the invoice whose
 * decline opened the case; {@code invoiceIds} are all its invoices, in the order they joined (Slice 3: one case
 * covers every unpaid invoice), and {@code unpaidInvoiceIds} the ones not paid yet.
 */
public record CaseView(UUID caseId, UUID accountId, UUID invoiceId, CaseStatus status, Instant openedAt,
        Instant closedAt, int retries, List<UUID> invoiceIds, List<UUID> unpaidInvoiceIds) {
}
