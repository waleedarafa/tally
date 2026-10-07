package com.tally.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
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
}
