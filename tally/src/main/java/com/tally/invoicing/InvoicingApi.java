package com.tally.invoicing;

import java.util.List;
import java.util.UUID;

/** Invoicing owns the billing calendar (ADR-002) and the mapping from payment references to invoices (ADR-014). */
public interface InvoicingApi {

    /** The account's invoices, oldest first. */
    List<InvoiceView> invoicesFor(UUID accountId);

    /** The subscription's invoices, oldest first. */
    List<InvoiceView> invoicesForSubscription(UUID subscriptionId);
}
