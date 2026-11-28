package com.tally.offers.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.offers.ChangeRequest;
import com.tally.offers.Period;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteRequest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every defined flag's state is recorded on the quote reason at quote time (WAR-66 step 4, ADR-010): an
 * account assignment overrides a country assignment, which overrides the default. Unit level: the in-memory
 * ports, no database.
 */
class QuoteFlagsTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final Period PERIOD = new Period(1, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"));
    static final UUID SUBSCRIPTION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    final InMemoryOffers ports = new InMemoryOffers();
    final PricingService pricing = new PricingService(ports, ports,
            new PricingRuleRegistry(List.of(new FixedPricePhaseRule(), new FreeTrialRule())), ports, new TestClock());

    final UUID accountA = ports.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "AE").id();
    final UUID accountB = ports.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "AE").id();
    final UUID accountC = ports.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "US").id();

    {
        publish("BASIC", "fixed-price-phase", Money.of(1200, "AED"));
        publish("PREMIUM", "fixed-price-phase", Money.of(2000, "AED"));
    }

    /** Publishes version 1 of an offer with one price (the kind is approved). */
    void publish(String id, String kind, Money price) {
        OfferKind approved = OfferKind.experimental(kind);
        approved.approve();
        Offer offer = Offer.create(id, id);
        offer.publish(approved, new OfferTerms(Map.of(price.currency(), price), 0),
                Instant.parse("2026-01-01T00:00:00Z"));
        ports.offers.put(id, offer);
    }

    QuoteRequest request(UUID account) {
        return new QuoteRequest(SUBSCRIPTION, account, "BASIC", 1, PERIOD);
    }

    ChangeRequest changeRequest(UUID account) {
        return new ChangeRequest(SUBSCRIPTION, account, "BASIC", 1, "PREMIUM", 1, PERIOD,
                LocalDate.parse("2026-01-16"));
    }

    @Test
    void emptyCatalogueRecordsEmptyFlagsOnQuoteAndChange() {
        PriceQuote quote = pricing.quote(request(accountA));
        assertThat(quote.reason().flags()).isEqualTo(Map.of());

        PriceQuote change = pricing.quoteChange(changeRequest(accountA));
        assertThat(change.reason().flags()).isEqualTo(Map.of());
    }

    @Test
    void everyDefinedFlagIsRecordedAndAccountOverridesCountry() {
        ports.flags.define("alpha", NOW);
        ports.flags.assignCountry("alpha", "AE", true, NOW);
        ports.flags.assignAccount("alpha", accountA, false, NOW);
        ports.flags.define("beta", NOW);
        ports.flags.setDefault("beta", true, NOW);

        Map<UUID, Map<String, Boolean>> expected = new HashMap<>();
        expected.put(accountA, Map.of("alpha", false, "beta", true));
        expected.put(accountB, Map.of("alpha", true, "beta", true));
        expected.put(accountC, Map.of("alpha", false, "beta", true));

        for (UUID account : List.of(accountA, accountB, accountC)) {
            PriceQuote quote = pricing.quote(request(account));
            assertThat(quote.reason().flags()).as("quote: " + account).isEqualTo(expected.get(account));

            PriceQuote change = pricing.quoteChange(changeRequest(account));
            assertThat(change.reason().flags()).as("quoteChange: " + account).isEqualTo(expected.get(account));
        }
    }
}
