package com.tally.invoicing.domain;

/**
 * Ports the external tax service (ADR-050, FR-016, D2-11): how much tax a jurisdiction charges on a
 * pre-tax invoice. Adapters translate external wire models into {@link TaxQuote} or throw
 * {@link TaxUnavailableException}; the domain never sees the wire.
 */
public interface TaxRates {

    /** The tax for one invoice's pre-tax amount, or {@link TaxUnavailableException} if it cannot be asked. */
    TaxQuote quote(TaxRequest request);
}
