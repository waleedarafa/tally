package com.tally.invoicing;

import java.util.List;
import java.util.UUID;

/** Invoicing owns the billing calendar (FR-002) and the mapping from payment references to invoices (FR-014). */
public interface InvoicingApi {

    /** The account's invoices, oldest first. */
    List<InvoiceView> invoicesFor(UUID accountId);

    /** The subscription's invoices, oldest first. */
    List<InvoiceView> invoicesForSubscription(UUID subscriptionId);

    /**
     * Collections' retry {@code retry} (1 on day 3) of an invoice whose payment was declined: asks Payment
     * to collect its reference again (design.md §4; Payment looks the reference up first, ADR-026).
     * Does nothing unless the invoice's payment failed. A retry asked for twice is made once.
     *
     * <p>Calls the provider through Payment, so it must not be called inside a transaction (rule 6).
     */
    void requestPaymentAgain(UUID invoiceId, int retry);
}
