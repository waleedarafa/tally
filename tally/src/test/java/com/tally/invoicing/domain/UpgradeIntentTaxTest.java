package com.tally.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteReason;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The upgrade intent carries the tax it was charged with (ADR-051): the amount charged is the net of
 * the two documents, VAT included, and it is computed from the same stored lines on every retry.
 */
class UpgradeIntentTaxTest {

    static final LocalDate PERIOD_START = LocalDate.parse("2026-04-01");
    static final LocalDate PERIOD_END = LocalDate.parse("2026-05-01");
    static final TaxQuote TAX = new TaxQuote(Ratio.of(5, 100), "VAT (5%)");

    final UUID subscriptionId = UUID.randomUUID();
    final UUID accountId = UUID.randomUUID();

    PriceQuote goldenQuote(List<PriceLine> lines) {
        Money total = lines.stream().map(PriceLine::amount).reduce(Money::plus).orElseThrow();
        return new PriceQuote(subscriptionId, "PRO", 2, new Period(1, PERIOD_START, PERIOD_END),
                lines, total, new QuoteReason("PRO", 2, "proration", Map.of()));
    }

    UpgradeIntent intentFor(PriceQuote quote) {
        return UpgradeIntent.place("INV-" + UUID.randomUUID(), subscriptionId, accountId, "PRO", 2,
                UUID.randomUUID(), quote, TAX);
    }

    @Test
    void chargeAndCreditAtFivePercent() {
        PriceQuote quote = goldenQuote(List.of(
                new PriceLine("Unused Basic", Money.of(-600, "AED")),
                new PriceLine("Premium remaining", Money.of(1000, "AED"))));
        UpgradeIntent intent = intentFor(quote);

        assertThat(intent.netCharge()).isEqualTo(Money.of(1000, "AED"));
        assertThat(intent.chargeTax()).isEqualTo(Money.of(50, "AED"));
        assertThat(intent.netCredit()).isEqualTo(Money.of(600, "AED"));
        assertThat(intent.creditTax()).isEqualTo(Money.of(30, "AED"));
        assertThat(intent.charge()).isEqualTo(Money.of(420, "AED"));
    }

    @Test
    void halfUpIsAppliedToEachLine() {
        PriceQuote quote = goldenQuote(List.of(
                new PriceLine("Unused Basic", Money.of(-601, "AED")),
                new PriceLine("Premium remaining", Money.of(1010, "AED"))));
        UpgradeIntent intent = intentFor(quote);

        assertThat(intent.chargeTax()).isEqualTo(Money.of(51, "AED"));
        assertThat(intent.creditTax()).isEqualTo(Money.of(30, "AED"));
        assertThat(intent.charge()).isEqualTo(Money.of(430, "AED"));
    }

    @Test
    void vatIsRoundedPerLineNotOnTheTotal() {
        PriceQuote quote = goldenQuote(List.of(
                new PriceLine("Line one", Money.of(333, "AED")),
                new PriceLine("Line two", Money.of(333, "AED"))));
        UpgradeIntent intent = intentFor(quote);

        assertThat(intent.chargeTax()).isEqualTo(Money.of(34, "AED"));
        assertThat(intent.netCredit()).isEqualTo(Money.of(0, "AED"));
        assertThat(intent.creditTax()).isEqualTo(Money.of(0, "AED"));
        assertThat(intent.charge()).isEqualTo(Money.of(700, "AED"));
    }

    @Test
    void noTaxLeavesTheQuoteTotal() {
        PriceQuote quote = goldenQuote(List.of(
                new PriceLine("Unused Basic", Money.of(-600, "AED")),
                new PriceLine("Premium remaining", Money.of(1000, "AED"))));

        UpgradeIntent withoutTax = UpgradeIntent.place("INV-" + UUID.randomUUID(), subscriptionId, accountId,
                "PRO", 2, UUID.randomUUID(), quote);

        assertThat(withoutTax.tax()).isEqualTo(TaxQuote.zero());
        assertThat(withoutTax.chargeTax()).isEqualTo(Money.of(0, "AED"));
        assertThat(withoutTax.creditTax()).isEqualTo(Money.of(0, "AED"));
        assertThat(withoutTax.charge()).isEqualTo(Money.of(400, "AED"));
        assertThat(withoutTax.charge()).isEqualTo(quote.total());

        UpgradeIntent explicitZero = UpgradeIntent.place("INV-" + UUID.randomUUID(), subscriptionId, accountId,
                "PRO", 2, UUID.randomUUID(), quote, TaxQuote.zero());

        assertThat(explicitZero.tax()).isEqualTo(TaxQuote.zero());
        assertThat(explicitZero.chargeTax()).isEqualTo(Money.of(0, "AED"));
        assertThat(explicitZero.creditTax()).isEqualTo(Money.of(0, "AED"));
        assertThat(explicitZero.charge()).isEqualTo(Money.of(400, "AED"));
    }

    @Test
    void completedKeepsTheTax() {
        PriceQuote quote = goldenQuote(List.of(
                new PriceLine("Unused Basic", Money.of(-600, "AED")),
                new PriceLine("Premium remaining", Money.of(1000, "AED"))));
        UpgradeIntent intent = intentFor(quote).completed(Instant.parse("2026-04-16T10:00:00Z"));

        assertThat(intent.tax()).isEqualTo(TAX);
        assertThat(intent.completed()).isTrue();
    }

    @Test
    void netChargeOfIgnoresCreditLines() {
        PriceQuote quote = goldenQuote(List.of(
                new PriceLine("Unused Basic", Money.of(-600, "AED")),
                new PriceLine("Premium remaining", Money.of(1000, "AED"))));

        assertThat(UpgradeIntent.netChargeOf(quote)).isEqualTo(Money.of(1000, "AED"));
    }
}
