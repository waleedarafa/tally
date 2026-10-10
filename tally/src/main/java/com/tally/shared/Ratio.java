package com.tally.shared;

/**
 * An exact fraction, such as 17 of 30 days or 5 percent, for {@link Money#times(Ratio)} (ADR-017).
 * Never a decimal: the one rounding happens in {@code times}.
 */
public record Ratio(long numerator, long denominator) {

    public Ratio {
        if (denominator <= 0) {
            throw new IllegalArgumentException("A ratio's denominator must be positive: " + denominator);
        }
    }

    public static Ratio of(long numerator, long denominator) {
        return new Ratio(numerator, denominator);
    }
}
