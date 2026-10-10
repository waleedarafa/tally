package com.tally.shared;

import java.math.BigInteger;

/**
 * An exact fraction, such as 17 of 30 days or 5 percent, for {@link Money#times(Ratio)} (ADR-017).
 * Never a decimal: the one rounding happens in {@code times}. Kept in lowest terms, so equal fractions
 * are equal ratios: {@code Ratio.of(2, 4).equals(Ratio.of(1, 2))}.
 */
public record Ratio(long numerator, long denominator) {

    public Ratio {
        if (denominator <= 0) {
            throw new IllegalArgumentException("A ratio's denominator must be positive: " + denominator);
        }
        long divisor = BigInteger.valueOf(numerator).gcd(BigInteger.valueOf(denominator)).longValueExact();
        numerator /= divisor;
        denominator /= divisor;
    }

    public static Ratio of(long numerator, long denominator) {
        return new Ratio(numerator, denominator);
    }
}
