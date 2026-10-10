package com.tally.offers.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.offers.Period;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteRequest;
import com.tally.offers.domain.FixedPricePhaseRule;
import com.tally.offers.domain.FreeTrialRule;
import com.tally.offers.domain.Offer;
import com.tally.offers.domain.OfferKind;
import com.tally.offers.domain.OfferTerms;
import com.tally.offers.domain.PricingRuleRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Builds a PricingService on in-memory ports, and quotes each golden example twice (O3). */
final class GoldenQuotes {

    static final UUID SUBSCRIPTION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    final InMemoryOffers ports = new InMemoryOffers();
    final PricingService pricing = new PricingService(ports, ports,
            new PricingRuleRegistry(List.of(new FixedPricePhaseRule(), new FreeTrialRule())));

    /** Publishes version 1 of an offer of the given kind. */
    void offer(String id, String kind, OfferTerms terms) {
        OfferKind approved = OfferKind.experimental(kind);
        approved.approve();
        Offer offer = Offer.create(id, id);
        offer.publish(approved, terms, Instant.parse("2026-01-01T00:00:00Z"));
        ports.offers.put(id, offer);
    }

    UUID account(String currency) {
        return ports.open(Currency.getInstance(currency), ZoneId.of("UTC")).id();
    }

    static Period period(int number) {
        LocalDate start = LocalDate.parse("2026-01-15").plusMonths(number - 1);
        return new Period(number, start, start.plusMonths(1));
    }

    /** O3: the same subscription, period and offer version give the same quote, every time. */
    PriceQuote quoteTwice(UUID account, String offer, Period period) {
        QuoteRequest request = new QuoteRequest(SUBSCRIPTION, account, offer, 1, period);
        PriceQuote first = pricing.quote(request);
        PriceQuote second = pricing.quote(request);
        assertThat(second).isEqualTo(first);
        System.out.println("GOLDEN " + first);
        return first;
    }
}
