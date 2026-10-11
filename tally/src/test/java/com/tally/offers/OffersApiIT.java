package com.tally.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.UnknownAccountException;
import com.tally.shared.Money;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Offers and Pricing's public API on the seeded offers: the first run's three plans, as version 1 (V17). */
class OffersApiIT extends AbstractIntegrationTest {

    @Autowired
    Pricing pricing;

    @Autowired
    Offers offers;

    @Autowired
    AccountApi accounts;

    UUID account(String currency) {
        return accounts.open(Currency.getInstance(currency), ZoneId.of("UTC")).id();
    }

    static final Period FIRST = new Period(1, LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"));

    @ParameterizedTest
    @CsvSource({"BASIC,USD,999", "BASIC,AED,3699", "STANDARD,USD,1999", "STANDARD,AED,7299",
            "PREMIUM,USD,2999", "PREMIUM,AED,10999"})
    void quotesEverySeededOfferAtItsFirstRunPrice(String offer, String currency, long minorUnits) {
        UUID subscription = UUID.randomUUID();

        PriceQuote quote = pricing.quote(new QuoteRequest(subscription, account(currency), offer, 1, FIRST));

        assertThat(quote.total()).isEqualTo(Money.of(minorUnits, currency));
        assertThat(quote.lines()).containsExactly(
                new PriceLine("Period 1, 2026-01-15 to 2026-02-15", Money.of(minorUnits, currency)));
        assertThat(quote.subscriptionId()).isEqualTo(subscription);
        assertThat(quote.offerId()).isEqualTo(offer);
        assertThat(quote.offerVersion()).isEqualTo(1);
        assertThat(quote.period()).isEqualTo(FIRST);
        assertThat(quote.reason()).isEqualTo(new QuoteReason(offer, 1, "fixed-price-phase", Map.of()));
        System.out.println("PRICE-QUOTE " + quote);
    }

    @Test
    void refusesUnknownOffersCurrenciesAndAccounts() {
        assertThatThrownBy(() -> pricing.quote(new QuoteRequest(UUID.randomUUID(), account("USD"), "GOLD", 1, FIRST)))
                .isInstanceOf(UnknownPlanException.class);
        assertThatThrownBy(() -> pricing.quote(new QuoteRequest(UUID.randomUUID(), account("EUR"), "BASIC", 1, FIRST)))
                .isInstanceOf(UnknownPlanException.class);
        assertThatThrownBy(() -> pricing.quote(new QuoteRequest(UUID.randomUUID(), UUID.randomUUID(), "BASIC", 1, FIRST)))
                .isInstanceOf(UnknownAccountException.class);
    }

    @Test
    void anOfferHasACurrentVersionWhereItHasAPriceInTheAccountsCurrency() {
        assertThat(offers.currentVersion("BASIC", account("AED"))).isPresent();
        assertThat(offers.currentVersion("BASIC", account("EUR"))).isEmpty();
        assertThat(offers.currentVersion("GOLD", account("USD"))).isEmpty();
        assertThatThrownBy(() -> offers.currentVersion("BASIC", UUID.randomUUID()))
                .isInstanceOf(UnknownAccountException.class);
    }
}
