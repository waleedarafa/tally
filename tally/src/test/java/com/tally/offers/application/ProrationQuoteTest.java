package com.tally.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.offers.ChangeRequest;
import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.UnknownPlanException;
import com.tally.offers.domain.FixedPricePhaseRule;
import com.tally.offers.domain.FreeTrialRule;
import com.tally.offers.domain.Offer;
import com.tally.offers.domain.OfferKind;
import com.tally.offers.domain.OfferTerms;
import com.tally.offers.domain.PricingRuleRegistry;
import com.tally.platform.TestClock;
import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden examples for the mid-period change path (O4, ADR-040): unused days are credited at the old
 * price, remaining days are charged at the new price. Days are the days of [start, end): a change on
 * day {@code k} of a {@code n}-day period uses {@code k - 1} days and leaves {@code n - k + 1}
 * remaining. Rounding happens only inside Money.times(Ratio), half up (ADR-017); totals are sums of
 * the lines, never re-rounded. Every quote runs twice (O3).
 */
class ProrationQuoteTest {

    static final UUID SUBSCRIPTION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** The Step 3 example period: 30 days, 2026-01-01 to 2026-01-31. */
    static final Period PERIOD = new Period(1, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"));

    final InMemoryOffers ports = new InMemoryOffers();
    final PricingService pricing = new PricingService(ports, ports,
            new PricingRuleRegistry(List.of(new FixedPricePhaseRule(), new FreeTrialRule())), ports, new TestClock());

    UUID aedAccount;
    UUID kwdAccount;

    {
        put("BASIC", "fixed-price-phase", Money.of(1200, "AED"));
        put("PREMIUM", "fixed-price-phase", Money.of(2000, "AED"));
        put("BASIC-KWD", "fixed-price-phase", Money.of(2500, "KWD"));
        put("PREMIUM-KWD", "fixed-price-phase", Money.of(3500, "KWD"));
        put("USD-ONLY", "fixed-price-phase", Money.of(1200, "USD"));
        aedAccount = ports.open(Currency.getInstance("AED"), ZoneId.of("UTC")).id();
        kwdAccount = ports.open(Currency.getInstance("KWD"), ZoneId.of("UTC")).id();
    }

    /** Publishes version 1 of an offer with one price (the kind is approved). */
    void put(String id, String kind, Money price) {
        OfferKind approved = OfferKind.experimental(kind);
        approved.approve();
        Offer offer = Offer.create(id, id);
        offer.publish(approved, new OfferTerms(Map.of(price.currency(), price), 0),
                Instant.parse("2026-01-01T00:00:00Z"));
        ports.offers.put(id, offer);
    }

    /** The quote for a change from {@code oldOfferId} to {@code newOfferId}, effective on {@code effectiveDate}. */
    PriceQuote quoteChange(String oldOfferId, String newOfferId, UUID account, Period period,
            LocalDate effectiveDate) {
        ChangeRequest request = new ChangeRequest(SUBSCRIPTION, account, oldOfferId, 1, newOfferId, 1,
                period, effectiveDate);
        PriceQuote first = pricing.quoteChange(request);
        PriceQuote second = pricing.quoteChange(request);
        assertThat(second).isEqualTo(first);
        return first;
    }

    @Test
    void theStep3GoldenCaseDay16Of30() {
        // Day 16 of 30: days 1-15 are used, days 16-30 remain, all at the same flat price.
        PriceQuote quote = quoteChange("BASIC", "PREMIUM", aedAccount, PERIOD, LocalDate.parse("2026-01-16"));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Credit, 15 of 30 days at the old price (12.00)", Money.of(-600, "AED")),
                new PriceLine("Charge, 15 of 30 days at the new price (20.00)", Money.of(1000, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(400, "AED"));
        assertThat(quote.subscriptionId()).isEqualTo(SUBSCRIPTION);
        assertThat(quote.offerId()).isEqualTo("PREMIUM");
        assertThat(quote.offerVersion()).isEqualTo(1);
        assertThat(quote.reason().offerId()).isEqualTo("PREMIUM");
        assertThat(quote.reason().offerVersion()).isEqualTo(1);
        assertThat(quote.reason().kind()).isEqualTo("fixed-price-phase");
    }

    @Test
    void anUnevenSplitRoundsHalfUp() {
        // Day 24 of 30: 23 days used, 7 remain. 20.00 x 7/30 = 4.6667... A half-up line is 4.67,
        // not the truncated 4.66. The 12.00 credit is exact at 2.80.
        PriceQuote quote = quoteChange("BASIC", "PREMIUM", aedAccount, PERIOD, LocalDate.parse("2026-01-24"));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Credit, 7 of 30 days at the old price (12.00)", Money.of(-280, "AED")),
                new PriceLine("Charge, 7 of 30 days at the new price (20.00)", Money.of(467, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(187, "AED"));
    }

    @Test
    void aChangeOnTheLastDayLeavesOneDayRemaining() {
        // Day 30 of 30: 29 days used, day 30 remains. 20.00 x 1/30 = 0.6667... A half-up line is 0.67.
        PriceQuote quote = quoteChange("BASIC", "PREMIUM", aedAccount, PERIOD, LocalDate.parse("2026-01-30"));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Credit, 1 of 30 days at the old price (12.00)", Money.of(-40, "AED")),
                new PriceLine("Charge, 1 of 30 days at the new price (20.00)", Money.of(67, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(27, "AED"));
    }

    @Test
    void aChangeOnTheFirstDayCreditsAndChargesTheWholePeriod() {
        // Day 1 of 30: no days used, all 30 remain.
        PriceQuote quote = quoteChange("BASIC", "PREMIUM", aedAccount, PERIOD, LocalDate.parse("2026-01-01"));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Credit, 30 of 30 days at the old price (12.00)", Money.of(-1200, "AED")),
                new PriceLine("Charge, 30 of 30 days at the new price (20.00)", Money.of(2000, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(800, "AED"));
    }

    @Test
    void aDowngradeQuotesANegativeTotal() {
        // Premium 20.00 to Basic 12.00 on day 16: the credit at the old price (10.00) exceeds the charge (6.00).
        PriceQuote quote = quoteChange("PREMIUM", "BASIC", aedAccount, PERIOD, LocalDate.parse("2026-01-16"));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Credit, 15 of 30 days at the old price (20.00)", Money.of(-1000, "AED")),
                new PriceLine("Charge, 15 of 30 days at the new price (12.00)", Money.of(600, "AED")));
        assertThat(quote.total()).isEqualTo(Money.of(-400, "AED"));
    }

    @Test
    void aThreeDecimalCurrencyKeepsItsMinorUnits() {
        // Day 11 of 30 in KWD: 10 days used, 20 remain. 2.500 x 20/30 = 1.6667: half up is 1.667.
        PriceQuote quote = quoteChange("BASIC-KWD", "PREMIUM-KWD", kwdAccount, PERIOD, LocalDate.parse("2026-01-11"));

        assertThat(quote.lines()).containsExactly(
                new PriceLine("Credit, 20 of 30 days at the old price (2.500)", Money.of(-1667, "KWD")),
                new PriceLine("Charge, 20 of 30 days at the new price (3.500)", Money.of(2333, "KWD")));
        assertThat(quote.total()).isEqualTo(Money.of(666, "KWD"));
        assertThat(quote.total().toDecimal()).isEqualByComparingTo("0.666");
    }

    @Test
    void aChangeOutsideThePeriodIsRefused() {
        assertThatThrownBy(() -> new ChangeRequest(SUBSCRIPTION, aedAccount, "BASIC", 1, "PREMIUM", 1, PERIOD,
                LocalDate.parse("2026-01-30").plusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChangeRequest(SUBSCRIPTION, aedAccount, "BASIC", 1, "PREMIUM", 1, PERIOD,
                LocalDate.parse("2026-01-01").minusDays(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownOfferIsRefusedWithTheSameMessageAsQuote() {
        assertThatThrownBy(() -> quoteChange("BASIC", "NOPE", aedAccount, PERIOD, LocalDate.parse("2026-01-16")))
                .isInstanceOf(UnknownPlanException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void anOfferWithoutAPriceInTheAccountsCurrencyIsRefused() {
        assertThatThrownBy(() -> quoteChange("BASIC", "USD-ONLY", aedAccount, PERIOD, LocalDate.parse("2026-01-16")))
                .isInstanceOf(UnknownPlanException.class)
                .hasMessageContaining("USD-ONLY");
    }
}
