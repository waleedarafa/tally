package com.tally.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountApiIT extends AbstractIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Test
    void opensAnAccountAndRecordsItsPaymentToken() {
        AccountView opened = accounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"));

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
}
