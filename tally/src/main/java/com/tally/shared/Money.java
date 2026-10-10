package com.tally.shared;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * An amount of money: minor units (cents, fils) and an ISO 4217 currency (CLAUDE.md rule 10, ADR-017).
 * Never a floating-point number. The number of minor units comes from the currency
 * ({@link Currency#getDefaultFractionDigits()}: AED 2, KWD 3, JPY 0). {@link #times(Ratio)} is the only
 * place that rounds; totals are sums of rounded lines; {@link #allocate(int...)} splits exactly.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency without minor units: " + currency);
        }
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    /** From a decimal amount such as 36.99 AED. Never rounds: a finer amount throws {@link ArithmeticException}. */
    public static Money of(BigDecimal amount, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        return new Money(amount.movePointRight(currency.getDefaultFractionDigits()).longValueExact(), currency);
    }

    /** The amount as a decimal in the currency's major unit, for display. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minorUnits, sameCurrency(other).minorUnits), currency);
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(minorUnits, sameCurrency(other).minorUnits), currency);
    }

    /** The one place rounding happens: to the minor unit, half up (away from zero at exactly one half). */
    public Money times(Ratio ratio) {
        BigDecimal product = new BigDecimal(BigInteger.valueOf(minorUnits).multiply(BigInteger.valueOf(ratio.numerator())));
        return new Money(product.divide(BigDecimal.valueOf(ratio.denominator()), 0, RoundingMode.HALF_UP)
                .longValueExact(), currency);
    }

    /**
     * Splits this amount by weights, largest remainder first: each part gets its share rounded towards
     * zero, and the units left over go one each to the parts with the largest remainders (ties to the
     * earlier part). The parts always sum to the whole.
     */
    public Money[] allocate(int... weights) {
        if (weights.length == 0 || Arrays.stream(weights).anyMatch(w -> w < 0) || Arrays.stream(weights).sum() == 0) {
            throw new IllegalArgumentException("Weights must be non-negative and not all zero: " + Arrays.toString(weights));
        }
        BigInteger whole = BigInteger.valueOf(minorUnits).abs();
        BigInteger weightSum = BigInteger.valueOf(Arrays.stream(weights).asLongStream().sum());
        long[] parts = new long[weights.length];
        BigInteger[] remainders = new BigInteger[weights.length];
        BigInteger allocated = BigInteger.ZERO;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] share = whole.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(weightSum);
            parts[i] = share[0].longValueExact();
            remainders[i] = share[1];
            allocated = allocated.add(share[0]);
        }
        // Fewer units than parts are left over: each share lost less than one unit.
        int left = whole.subtract(allocated).intValueExact();
        int[] byRemainder = IntStream.range(0, weights.length).boxed()
                .sorted(Comparator.comparing((Integer i) -> remainders[i]).reversed())
                .mapToInt(Integer::intValue).toArray();
        for (int i = 0; i < left; i++) {
            parts[byRemainder[i]]++;
        }
        return Arrays.stream(parts)
                .mapToObj(p -> new Money(minorUnits < 0 ? Math.negateExact(p) : p, currency))
                .toArray(Money[]::new);
    }

    private Money sameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot mix " + currency + " and " + other.currency);
        }
        return other;
    }
}
