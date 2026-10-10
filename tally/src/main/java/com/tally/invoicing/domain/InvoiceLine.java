package com.tally.invoicing.domain;

import com.tally.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One line of an invoice: an amount from a PriceQuote, never computed here (R2, I3). */
@Embeddable
public record InvoiceLine(
        @Column(nullable = false) String description,
        @Column(name = "amount_minor", nullable = false) long amountMinor,
        @Column(nullable = false) String currency) {

    public static InvoiceLine of(String description, Money amount) {
        return new InvoiceLine(description, amount.minorUnits(), amount.currency().getCurrencyCode());
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }
}
