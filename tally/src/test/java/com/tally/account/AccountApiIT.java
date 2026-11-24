package com.tally.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.support.TestAccounts;
import java.time.Instant;
import java.time.ZoneId;
import java.util.OptionalInt;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountApiIT extends AbstractIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Test
    void opensAnAccountAndRecordsItsPaymentToken() {
        AccountView opened = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"));

        accounts.recordPaymentToken(opened.id(), "tok_123");

        AccountView read = accounts.get(opened.id()).orElseThrow();
        assertThat(read.currency().getCurrencyCode()).isEqualTo("AED");
        assertThat(read.timeZone()).isEqualTo(ZoneId.of("Asia/Dubai"));
        assertThat(read.paymentToken()).contains("tok_123");
    }

    @Test
    void anUnknownAccountIsEmptyAndCannotTakeAToken() {
        UUID unknown = UUID.randomUUID();

        assertThat(accounts.get(unknown)).isEmpty();
        assertThatThrownBy(() -> accounts.recordPaymentToken(unknown, "tok")).isInstanceOf(UnknownAccountException.class);
    }


    @Test
    void setsTheBillingDayOnceFromAStartInstant() {
        AccountView opened = testAccounts.open(Currency.getInstance("USD"), ZoneId.of("UTC"));

        assertThat(accounts.setBillingDayFrom(opened.id(), Instant.parse("2026-04-05T10:00:00Z"))).isTrue();
        assertThat(accounts.get(opened.id()).orElseThrow().billingDay()).isEqualTo(OptionalInt.of(5));
        assertThat(accounts.setBillingDayFrom(opened.id(), Instant.parse("2026-04-20T10:00:00Z"))).isFalse();
        assertThat(accounts.get(opened.id()).orElseThrow().billingDay()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void theBillingDayIsTheLocalDayInTheAccountTimeZone() {
        AccountView opened = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"));

        assertThat(accounts.setBillingDayFrom(opened.id(), Instant.parse("2026-01-31T22:00:00Z"))).isTrue();
        assertThat(accounts.get(opened.id()).orElseThrow().billingDay()).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void anUnknownAccountCannotSetABillingDay() {
        assertThatThrownBy(() -> accounts.setBillingDayFrom(UUID.randomUUID(), Instant.parse("2026-04-05T10:00:00Z")))
                .isInstanceOf(UnknownAccountException.class);
    }
}
