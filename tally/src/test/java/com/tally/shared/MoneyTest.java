package com.tally.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Currency;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void holdsMinorUnitsAndAnIsoCurrency() {
        Money price = Money.of(3699, "AED");

        assertThat(price.minorUnits()).isEqualTo(3699);
        assertThat(price.currency()).isEqualTo(Currency.getInstance("AED"));
        assertThat(price).isEqualTo(new Money(3699, Currency.getInstance("AED")));
    }

    @Test
    void rejectsUnknownOrMissingCurrencies() {
        assertThatThrownBy(() -> Money.of(100, "XYZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(NullPointerException.class);
    }

    /** A currency without minor units (gold, "no currency") cannot be money here. */
    @Test
    void rejectsCurrenciesWithoutMinorUnits() {
        assertThatThrownBy(() -> Money.of(100, "XAU")).isInstanceOf(IllegalArgumentException.class);
    }

    // Golden examples, design.md §6 (ADR-017).

    @Test
    void goldenProrationRoundsHalfUp() {
        assertThat(Money.of(2500, "AED").times(Ratio.of(17, 30))).isEqualTo(Money.of(1417, "AED"));
    }

    @Test
    void goldenPercentageRoundsHalfUp() {
        assertThat(Money.of(1417, "AED").times(Ratio.of(5, 100))).isEqualTo(Money.of(71, "AED"));
    }

    @Test
    void goldenTotalIsTheSumOfRoundedLinesNeverRoundedAgain() {
        assertThat(Money.of(1417, "AED").plus(Money.of(71, "AED"))).isEqualTo(Money.of(1488, "AED"));
    }

    @Test
    void goldenAllocationGivesTheRemainderToTheLargestRemainderFirst() {
        assertThat(Money.of(1000, "AED").allocate(1, 1, 1))
                .containsExactly(Money.of(334, "AED"), Money.of(333, "AED"), Money.of(333, "AED"));
    }

    @Test
    void goldenKuwaitiDinarHasThreeDecimals() {
        Money oneDinar = Money.of(new BigDecimal("1.000"), "KWD");

        assertThat(oneDinar).isEqualTo(Money.of(1000, "KWD"));
        assertThat(oneDinar.times(Ratio.of(1, 3))).isEqualTo(Money.of(333, "KWD"));
        assertThat(oneDinar.times(Ratio.of(1, 3)).toDecimal()).isEqualByComparingTo("0.333");
    }

    // Minor units come from the currency, never from a hard-coded 100.

    @Test
    void decimalAmountsUseTheCurrencysMinorUnits() {
        assertThat(Money.of(new BigDecimal("36.99"), "AED")).isEqualTo(Money.of(3699, "AED"));
        assertThat(Money.of(new BigDecimal("1.250"), "KWD")).isEqualTo(Money.of(1250, "KWD"));
        assertThat(Money.of(new BigDecimal("500"), "JPY")).isEqualTo(Money.of(500, "JPY"));
        assertThat(Money.of(1250, "KWD").toDecimal()).isEqualByComparingTo("1.250");
    }

    /** Converting a decimal never rounds: rounding happens only in times(Ratio). */
    @Test
    void aDecimalFinerThanTheMinorUnitIsRefused() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.005"), "AED")).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(new BigDecimal("0.5"), "JPY")).isInstanceOf(ArithmeticException.class);
    }

    // Arithmetic.

    @Test
    void plusAndMinusKeepTheCurrency() {
        assertThat(Money.of(1000, "USD").minus(Money.of(1250, "USD"))).isEqualTo(Money.of(-250, "USD"));
        assertThat(Money.of(-250, "USD").plus(Money.of(250, "USD"))).isEqualTo(Money.of(0, "USD"));
    }

    @Test
    void currenciesNeverMix() {
        assertThatThrownBy(() -> Money.of(100, "USD").plus(Money.of(100, "AED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(100, "USD").minus(Money.of(100, "AED")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowIsAnErrorNotAWrapAround() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "USD").plus(Money.of(1, "USD")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "USD").times(Ratio.of(2, 1)))
                .isInstanceOf(ArithmeticException.class);
    }

    /** Half up means away from zero at exactly one half, for credits as for charges. */
    @Test
    void halfUpAtExactlyOneHalf() {
        assertThat(Money.of(1, "USD").times(Ratio.of(1, 2))).isEqualTo(Money.of(1, "USD"));
        assertThat(Money.of(-1, "USD").times(Ratio.of(1, 2))).isEqualTo(Money.of(-1, "USD"));
        assertThat(Money.of(3, "USD").times(Ratio.of(1, 4))).isEqualTo(Money.of(1, "USD"));
    }

    @Test
    void timesDoesNotOverflowInTheMiddle() {
        // The product exceeds a long; the result does not.
        assertThat(Money.of(Long.MAX_VALUE, "USD").times(Ratio.of(Long.MAX_VALUE, Long.MAX_VALUE)))
                .isEqualTo(Money.of(Long.MAX_VALUE, "USD"));
    }

    @Test
    void allocateRefusesWeightsThatCannotSplit() {
        Money money = Money.of(100, "USD");
        assertThatThrownBy(money::allocate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> money.allocate(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> money.allocate(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allocatingANegativeAmountMirrorsThePositiveOne() {
        assertThat(Money.of(-1000, "AED").allocate(1, 1, 1))
                .containsExactly(Money.of(-334, "AED"), Money.of(-333, "AED"), Money.of(-333, "AED"));
    }

    /**
     * Property: for any amount and weights, the parts sum exactly to the whole, and each part is within
     * one minor unit of its exact share. A seeded loop, so a failure can be replayed.
     */
    @Test
    void allocationAlwaysSumsToTheWhole() {
        Random random = new Random(20261010);
        for (int run = 0; run < 10_000; run++) {
            long total = random.nextLong(-1_000_000_000_000L, 1_000_000_000_000L);
            int[] weights = new int[random.nextInt(1, 12)];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = random.nextInt(0, 1_000);
            }
            if (Arrays.stream(weights).sum() == 0) {
                weights[0] = 1;
            }
            Money whole = Money.of(total, "KWD");

            Money[] parts = whole.allocate(weights);

            String seen = "total " + total + ", weights " + Arrays.toString(weights) + ", parts " + Arrays.toString(parts);
            assertThat(parts).as(seen).hasSize(weights.length);
            assertThat(Arrays.stream(parts).mapToLong(Money::minorUnits).sum()).as(seen).isEqualTo(total);
            long weightSum = Arrays.stream(weights).sum();
            for (int i = 0; i < parts.length; i++) {
                BigDecimal exact = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(weights[i]))
                        .divide(BigDecimal.valueOf(weightSum), 6, RoundingMode.HALF_UP);
                assertThat(BigDecimal.valueOf(parts[i].minorUnits()).subtract(exact).abs())
                        .as(seen).isLessThan(BigDecimal.ONE);
                assertThat(parts[i].currency()).isEqualTo(whole.currency());
            }
        }
    }
}
