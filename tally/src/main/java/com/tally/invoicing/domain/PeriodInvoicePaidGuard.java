package com.tally.invoicing.domain;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.PeriodInvoiceUnpaidException;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-042: a paid-period-invoice guard. The caller passes this the period invoice for the subscription and
 * period it is about to act on, and the guard refuses anything not paid.
 */
public final class PeriodInvoicePaidGuard {

    private PeriodInvoicePaidGuard() {
    }

    public static Invoice requirePaid(Optional<Invoice> periodInvoice, UUID subscriptionId, int periodNumber) {
        Invoice invoice = periodInvoice.orElseThrow(() ->
                new PeriodInvoiceUnpaidException(subscriptionId, periodNumber));
        if (invoice.status() != InvoiceStatus.PAID) {
            throw new PeriodInvoiceUnpaidException(subscriptionId, periodNumber);
        }
        return invoice;
    }
}
