package com.tally.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RatioTest {

    /** Exact: kept as given, never turned into a decimal. */
    @Test
    void holdsANumeratorAndADenominator() {
        Ratio ratio = Ratio.of(17, 30);

        assertThat(ratio.numerator()).isEqualTo(17);
        assertThat(ratio.denominator()).isEqualTo(30);
    }

    /** Reduced by the greatest common divisor, so equal fractions are equal ratios. */
    @Test
    void equalFractionsAreEqualRatios() {
        assertThat(Ratio.of(2, 4)).isEqualTo(Ratio.of(1, 2));
        assertThat(Ratio.of(2, 4).numerator()).isEqualTo(1);
        assertThat(Ratio.of(2, 4).denominator()).isEqualTo(2);
        assertThat(Ratio.of(-6, 4)).isEqualTo(Ratio.of(-3, 2));
        assertThat(Ratio.of(0, 30)).isEqualTo(Ratio.of(0, 1));
        assertThat(Ratio.of(17, 30)).isEqualTo(new Ratio(17, 30));
    }

    @Test
    void theDenominatorIsPositive() {
        assertThatThrownBy(() -> Ratio.of(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ratio.of(1, -3)).isInstanceOf(IllegalArgumentException.class);
    }
}
