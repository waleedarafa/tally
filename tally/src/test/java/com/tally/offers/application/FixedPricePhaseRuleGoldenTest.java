package com.tally.offers.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteReason;
import com.tally.offers.domain.OfferTerms;
import com.tally.shared.Money;
import java.util.Currency;
import java.util.Map;
import com.tally.offers.Period;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Golden examples for FixedPricePhaseRule (ADR-015): one line, the period's price. Each runs twice (O3). */
class FixedPricePhaseRuleGoldenTest {

    final GoldenQuotes golden = new GoldenQuotes();

    {
        golden.offer("BASIC", "fixed-price-phase", new OfferTerms(Map.of(
                Currency.getInstance("USD"), Money.of(999, "USD"),
                Currency.getInstance("AED"), Money.of(3699, "AED"),
                Currency.getInstance("KWD"), Money.of(3500, "KWD")), 0));
    }

    @Test
    void theFirstPeriodCostsThePrice() {
        PriceQuote quote = golden.quoteTwice(golden.account("USD"), "BASIC", GoldenQuotes.period(1));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Period 1, 2026-01-15 to 2026-02-15", Money.of(999, "USD")));
        assertThat(quote.total()).isEqualTo(Money.of(999, "USD"));
        assertThat(quote.offerVersion()).isEqualTo(1);
        assertThat(quote.reason()).isEqualTo(new QuoteReason("BASIC", 1, "fixed-price-phase", Map.of()));
    }

    @Test
    void everyLaterPeriodCostsTheSame() {
        PriceQuote quote = golden.quoteTwice(golden.account("AED"), "BASIC", GoldenQuotes.period(7));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Period 7, 2026-07-15 to 2026-08-15", Money.of(3699, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(3699, "AED"));
    }

    @Test
    void aThreeDecimalCurrencyKeepsItsMinorUnits() {
        PriceQuote quote = golden.quoteTwice(golden.account("KWD"), "BASIC", GoldenQuotes.period(1));

        assertThat(quote.total()).isEqualTo(Money.of(3500, "KWD"));
        assertThat(quote.total().toDecimal()).isEqualByComparingTo("3.500");
    }


    @Test
    void aShortFirstPeriodIsPricedByDays() {
        UUID account = golden.account("USD");
        golden.ports.setBillingDayFrom(account, Instant.parse("2026-01-15T10:00:00Z"));
        Period period = new Period(1, LocalDate.parse("2026-01-25"), LocalDate.parse("2026-02-15"));
        PriceQuote quote = golden.quoteTwice(account, "BASIC", period);

        // [2026-01-25, 2026-02-15) is 21 days; the regular period ending 2026-02-15 is [2026-01-15, 2026-02-15), 31 days.
        assertThat(quote.lines()).containsExactly(
                new PriceLine("Period 1, 2026-01-25 to 2026-02-15", Money.of(677, "USD"))); // 999 * 21/31 half up
        assertThat(quote.total()).isEqualTo(Money.of(677, "USD"));
    }

    @Test
    void aRenewalIsTheFullPriceWhateverTheAccountBillingDay() {
        UUID account = golden.account("AED");
        golden.ports.setBillingDayFrom(account, Instant.parse("2026-02-05T10:00:00Z"));
        golden.offer("BASIC-3100", "fixed-price-phase", new OfferTerms(Map.of(
                Currency.getInstance("AED"), Money.of(3100, "AED")), 0));
        Period period = new Period(2, LocalDate.parse("2026-02-20"), LocalDate.parse("2026-03-20"));

        PriceQuote quote = golden.quoteTwice(account, "BASIC-3100", period);

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Period 2, 2026-02-20 to 2026-03-20", Money.of(3100, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(3100, "AED"));
    }
}
