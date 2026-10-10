package com.tally.offers;

import com.tally.shared.Money;
import java.util.Objects;

/** One line of a quote, and of the invoice made from it (I3). A credit is a negative amount. */
public record PriceLine(String description, Money amount) {

    public PriceLine {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(amount, "amount");
    }
}
