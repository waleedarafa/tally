package com.tally.invoicing.domain;

/**
 * What kind of document the invoice is (ADR-040): the period invoice, at most one per subscription per
 * period, or an adjustment for that period, a different document with its own ID, tied to the period
 * invoice. The column is stored as STRING.
 */
public enum InvoiceKind {
    PERIOD,
    ADJUSTMENT
}
