package com.tally.invoicing;

/** Brief section 1. A failed payment is recorded only; slice 1 has no retries or dunning. */
public enum InvoiceStatus {
    PAYMENT_PENDING, PAID, PAYMENT_FAILED
}
