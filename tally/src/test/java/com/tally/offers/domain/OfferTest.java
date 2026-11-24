package com.tally.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfferTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final Currency USD = Currency.getInstance("USD");
    static final OfferTerms TERMS = new OfferTerms(Map.of(USD, Money.of(999, "USD")), 0);

    static OfferKind approved(String id) {
        OfferKind kind = OfferKind.experimental(id);
        kind.approve();
        return kind;
    }

    /** O1: a new kind of offer is published only after finance approves the kind. */
    @Test
    void anOfferOfAnExperimentalKindCannotBePublished() {
        Offer offer = Offer.create("BASIC", "Basic");

        assertThatThrownBy(() -> offer.publish(OfferKind.experimental("bundle"), TERMS, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bundle");
        assertThat(offer.versions()).isEmpty();
    }

    @Test
    void approvalIsNeverWithdrawn() {
        OfferKind kind = approved("fixed-price-phase");

        kind.approve();

        assertThat(kind.approved()).isTrue();
    }

    /** O2: a published version never changes; a change makes a new version. */
    @Test
    void aChangeMakesANewVersionAndLeavesTheOldOneAsItWas() {
        Offer offer = Offer.create("BASIC", "Basic");
        OfferVersion first = offer.publish(approved("fixed-price-phase"), TERMS, NOW);

        OfferVersion second = offer.publish(approved("fixed-price-phase"),
                new OfferTerms(Map.of(USD, Money.of(1099, "USD")), 0), NOW.plusSeconds(60));

        assertThat(first.number()).isEqualTo(1);
        assertThat(second.number()).isEqualTo(2);
        assertThat(offer.version(1)).contains(first);
        assertThat(offer.version(1).orElseThrow().terms().priceIn(USD)).contains(Money.of(999, "USD"));
        assertThat(offer.current()).contains(second);
        assertThatThrownBy(() -> offer.versions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void termsPriceEachCurrencyInThatCurrency() {
        assertThatThrownBy(() -> new OfferTerms(Map.of(USD, Money.of(999, "AED")), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OfferTerms(Map.of(USD, Money.of(999, "USD")), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Golden A-06: publish on 20 October with a 1 November effective date leaves v1 in force through 31 October. */
    @Test
    void goldenA06() {
        Offer offer = Offer.create("BASIC", "Basic");
        OfferVersion v1 = offer.publish(approved("fixed-price-phase"), TERMS,
                Instant.parse("2026-01-01T00:00:00Z"), LocalDate.parse("2026-01-01"));

        OfferVersion v2 = offer.publish(approved("fixed-price-phase"),
                new OfferTerms(Map.of(USD, Money.of(1099, "USD")), 0),
                Instant.parse("2026-10-20T10:00:00Z"), LocalDate.parse("2026-11-01"));

        assertThat(v1.number()).isEqualTo(1);
        assertThat(v2.number()).isEqualTo(2);
        assertThat(offer.currentOn(LocalDate.parse("2026-10-20"))).contains(v1);
        assertThat(offer.currentOn(LocalDate.parse("2026-10-31"))).contains(v1);
        assertThat(offer.currentOn(LocalDate.parse("2026-11-01"))).contains(v2);
        assertThat(offer.version(1).orElseThrow().terms().priceIn(USD)).contains(Money.of(999, "USD"));
    }

    @Test
    void currentOnIsEmptyBeforeTheFirstVersionTakesEffect() {
        Offer offer = Offer.create("BASIC", "Basic");
        offer.publish(approved("fixed-price-phase"), TERMS,
                Instant.parse("2026-01-01T00:00:00Z"), LocalDate.parse("2026-01-01"));

        assertThat(offer.currentOn(LocalDate.parse("2025-12-31"))).isEmpty();
    }

    @Test
    void anEarlierEffectiveFromIsRefusedAndNothingIsAdded() {
        Offer offer = Offer.create("BASIC", "Basic");
        offer.publish(approved("fixed-price-phase"), TERMS,
                Instant.parse("2026-01-01T00:00:00Z"), LocalDate.parse("2026-01-01"));

        assertThatThrownBy(() -> offer.publish(approved("fixed-price-phase"), TERMS,
                Instant.parse("2026-01-02T10:00:00Z"), LocalDate.parse("2025-12-01")))
                        .isInstanceOf(IllegalArgumentException.class);

        assertThat(offer.versions()).hasSize(1);
    }

    @Test
    void theLaterNumberWinsWhenTwoVersionsShareAnEffectiveFrom() {
        Offer offer = Offer.create("BASIC", "Basic");
        offer.publish(approved("fixed-price-phase"), TERMS,
                Instant.parse("2026-01-01T00:00:00Z"), LocalDate.parse("2026-11-01"));

        OfferVersion v2 = offer.publish(approved("fixed-price-phase"), TERMS,
                Instant.parse("2026-10-20T10:00:00Z"), LocalDate.parse("2026-11-01"));
        OfferVersion v3 = offer.publish(approved("fixed-price-phase"), TERMS,
                Instant.parse("2026-10-21T10:00:00Z"), LocalDate.parse("2026-11-01"));

        assertThat(v2.number()).isEqualTo(2);
        assertThat(v3.number()).isEqualTo(3);
        assertThat(offer.currentOn(LocalDate.parse("2026-11-01"))).contains(v3);
    }
}
