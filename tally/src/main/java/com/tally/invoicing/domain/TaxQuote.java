package com.tally.invoicing.domain;

import com.tally.shared.Ratio;
import java.util.Objects;

/**
 * The tax a jurisdiction quotes for an invoice (ADR-050): an exact rate and a description for the
 * line it is appended as. A zero rate quotes no tax.
 */
public record TaxQuote(
        Ratio rate,
        String description) {

    public TaxQuote {
        Objects.requireNonNull(rate, "rate");
        if (rate.numerator() < 0) {
            throw new IllegalArgumentException("A rate cannot be negative: " + rate);
        }
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("A tax quote's description must not be blank");
        }
    }

    /** The quote for tax-exempt jurisdictions: no tax, and no tax line is added. */
    public static TaxQuote zero() {
        return new TaxQuote(Ratio.of(0, 100), "VAT (0%)");
    }
}
