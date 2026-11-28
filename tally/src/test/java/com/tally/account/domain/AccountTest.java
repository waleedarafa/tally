package com.tally.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The account's billing day (ADR-043): set once, by the first subscription, and never changed. */
class AccountTest {

    private static Account account(ZoneId timeZone) {
        return new Account(UUID.randomUUID(), Currency.getInstance("USD"), timeZone,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void aNewAccountHasAnEmptyBillingDay() {
        assertThat(account(ZoneId.of("UTC")).billingDay()).isEmpty();
    }

    @Test
    void theFirstSubscriptionSetsTheDayFromItsStartInstantInTheAccountTimeZone() {
        Account account = account(ZoneId.of("UTC"));
        assertThat(account.setBillingDayFrom(Instant.parse("2026-04-05T10:00:00Z"))).isTrue();
        assertThat(account.billingDay()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void aSecondCallIsRefusedAndLeavesTheDayUnchanged() {
        Account account = account(ZoneId.of("UTC"));
        assertThat(account.setBillingDayFrom(Instant.parse("2026-04-05T10:00:00Z"))).isTrue();
        assertThat(account.setBillingDayFrom(Instant.parse("2026-04-20T10:00:00Z"))).isFalse();
        assertThat(account.billingDay()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void theDayIsTheLocalDayInTheAccountTimeZone() {
        Account account = account(ZoneId.of("Asia/Dubai"));
        assertThat(account.setBillingDayFrom(Instant.parse("2026-01-31T22:00:00Z"))).isTrue();
        assertThat(account.billingDay()).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void theDayCanBeThirtyOne() {
        Account account = account(ZoneId.of("UTC"));
        assertThat(account.setBillingDayFrom(Instant.parse("2026-01-31T10:00:00Z"))).isTrue();
        assertThat(account.billingDay()).isEqualTo(OptionalInt.of(31));
    }

    private static Account account(ZoneId timeZone, String country) {
        return new Account(UUID.randomUUID(), Currency.getInstance("USD"), timeZone, country,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void aNewAccountOpenedWithTheFourArgumentConstructorHasCountryUs() {
        assertThat(account(ZoneId.of("UTC")).country()).isEqualTo("US");
    }

    @Test
    void theFiveArgumentConstructorKeepsAValidCode() {
        assertThat(account(ZoneId.of("UTC"), "AE").country()).isEqualTo("AE");
    }

    @Test
    void lowercaseAndPaddedCodesAreNormalisedToUpperCase() {
        assertThat(account(ZoneId.of("UTC"), "ae").country()).isEqualTo("AE");
        assertThat(account(ZoneId.of("UTC"), " ae ").country()).isEqualTo("AE");
    }

    @Test
    void missingAndInvalidCodesAreRejected() {
        assertThatThrownBy(() -> account(ZoneId.of("UTC"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account(ZoneId.of("UTC"), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account(ZoneId.of("UTC"), "  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account(ZoneId.of("UTC"), "USA")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account(ZoneId.of("UTC"), "A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account(ZoneId.of("UTC"), "A1")).isInstanceOf(IllegalArgumentException.class);
    }
}
