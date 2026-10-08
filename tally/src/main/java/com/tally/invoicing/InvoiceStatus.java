package com.tally.invoicing;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Brief section 1. A failed payment is recorded only; slice 1 has no retries or dunning. Crosses
 * module boundaries ({@link InvoiceView}), so it has an {@code UNKNOWN} default for values this
 * version does not know (CLAUDE.md rule 7). Never stored: the invoice table's check rejects it.
 */
public enum InvoiceStatus {
    PAYMENT_PENDING, PAID, PAYMENT_FAILED,
    @JsonEnumDefaultValue
    UNKNOWN
}
