package com.tally.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.shared.Money;
import java.time.Instant;
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
}
