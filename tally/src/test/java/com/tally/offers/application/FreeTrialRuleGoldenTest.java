package com.tally.offers.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteReason;
import com.tally.offers.domain.OfferTerms;
import com.tally.shared.Money;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden examples for FreeTrialRule (ADR-015): during the free periods, the price and a credit of the
 * same amount, so the lines show what the trial is worth and the total is zero; afterwards, the price.
 * Each runs twice (O3).
 */
class FreeTrialRuleGoldenTest {

    final GoldenQuotes golden = new GoldenQuotes();

    {
        Map<Currency, Money> prices = Map.of(Currency.getInstance("AED"), Money.of(3699, "AED"));
        golden.offer("TRIAL1", "free-trial", new OfferTerms(prices, 1));
        golden.offer("TRIAL3", "free-trial", new OfferTerms(prices, 3));
    }

    @Test
    void theFreePeriodShowsThePriceAndAnEqualCredit() {
        PriceQuote quote = golden.quoteTwice(golden.account("AED"), "TRIAL1", GoldenQuotes.period(1));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Period 1, 2026-01-15 to 2026-02-15", Money.of(3699, "AED")),
                new PriceLine("Free trial, period 1 of 1", Money.of(-3699, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(0, "AED"));
        assertThat(quote.reason()).isEqualTo(new QuoteReason("TRIAL1", 1, "free-trial", Map.of()));
    }

    @Test
    void theFirstPeriodAfterTheTrialCostsThePrice() {
        PriceQuote quote = golden.quoteTwice(golden.account("AED"), "TRIAL1", GoldenQuotes.period(2));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Period 2, 2026-02-15 to 2026-03-15", Money.of(3699, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(3699, "AED"));
    }

    @Test
    void aLongerTrialEndsAfterItsLastFreePeriod() {
        assertThat(golden.quoteTwice(golden.account("AED"), "TRIAL3", GoldenQuotes.period(3)).total())
                .isEqualTo(Money.of(0, "AED"));
        assertThat(golden.quoteTwice(golden.account("AED"), "TRIAL3", GoldenQuotes.period(4)).total())
                .isEqualTo(Money.of(3699, "AED"));
    }
}
