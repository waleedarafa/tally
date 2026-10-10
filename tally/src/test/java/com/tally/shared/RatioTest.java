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

    @Test
    void theDenominatorIsPositive() {
        assertThatThrownBy(() -> Ratio.of(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ratio.of(1, -3)).isInstanceOf(IllegalArgumentException.class);
    }
}
