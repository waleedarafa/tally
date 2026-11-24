package com.tally.invoicing;

import java.util.UUID;

public class PeriodInvoiceUnpaidException extends RuntimeException {

    public PeriodInvoiceUnpaidException(UUID subscriptionId, int periodNumber) {
        super("Period " + periodNumber + " of subscription " + subscriptionId + " is not paid");
    }
}
