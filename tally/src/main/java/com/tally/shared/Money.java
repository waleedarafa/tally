package com.tally.shared;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money: minor units (cents, fils) and an ISO 4217 currency (CLAUDE.md rule 10).
 * Never a floating-point number.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }
}
