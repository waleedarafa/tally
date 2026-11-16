package com.tally.collections;

import java.util.List;
import java.util.UUID;

/**
 * A "pay now" request (Slice 3, decision 2): Collections asked Invoicing again, with retry number {@code retry}, for
 * each invoice in {@code invoiceIds}, the unpaid invoices of the case. The outcome arrives later.
 */
public record PaymentRequestView(UUID caseId, int retry, List<UUID> invoiceIds) {
}
