package com.tally.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.UnknownAccountException;
import com.tally.shared.Money;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

class CatalogApiIT extends AbstractIntegrationTest {

    @Autowired
    CatalogApi catalog;

    @Autowired
    AccountApi accounts;

    UUID account(String currency) {
        return accounts.open(Currency.getInstance(currency), ZoneId.of("UTC")).id();
    }

    @ParameterizedTest
    @CsvSource({"BASIC,USD,999", "BASIC,AED,3699", "STANDARD,USD,1999", "STANDARD,AED,7299",
            "PREMIUM,USD,2999", "PREMIUM,AED,10999"})
    void quotesEveryPlanInTheAccountsCurrency(String plan, String currency, long minorUnits) {
        PriceQuote quote = catalog.quote(account(currency), plan, BillingPeriod.MONTHLY);

        assertThat(quote.price()).isEqualTo(Money.of(minorUnits, currency));
        assertThat(quote.planId()).isEqualTo(plan);
    }

    @Test
    void refusesUnknownPlansCurrenciesAndAccounts() {
        assertThatThrownBy(() -> catalog.quote(account("USD"), "GOLD", BillingPeriod.MONTHLY))
                .isInstanceOf(UnknownPlanException.class);
        assertThatThrownBy(() -> catalog.quote(account("EUR"), "BASIC", BillingPeriod.MONTHLY))
                .isInstanceOf(UnknownPlanException.class);
        assertThatThrownBy(() -> catalog.quote(UUID.randomUUID(), "BASIC", BillingPeriod.MONTHLY))
                .isInstanceOf(UnknownAccountException.class);
    }
}
