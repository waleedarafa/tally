package com.tally.invoicing.domain;

import com.tally.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

/**
 * One question to {@link TaxRates} (ADR-050): the jurisdiction, the currency and amount the invoice
 * nets, and the tax point date — the invoice date in the account's local time zone.
 */
public record TaxRequest(
        String country,
        Currency currency,
        Money netAmount,
        LocalDate taxPointDate) {

    public TaxRequest {
        Objects.requireNonNull(country, "country");
        if (country.isBlank()) {
            throw new IllegalArgumentException("country must not be blank");
        }
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(netAmount, "netAmount");
        Objects.requireNonNull(taxPointDate, "taxPointDate");
    }
}
