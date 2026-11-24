package com.tally.offers;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.offers.domain.Offer;
import com.tally.offers.domain.OfferCatalogue;
import com.tally.offers.domain.OfferVersion;
import com.tally.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-047 (WAR-65): each offer version carries effectiveFrom, the local date it takes effect on. That
 * is what currentOn reads, not publishedAt or current(); publishedAt remains the instant of publication.
 */
class OfferVersionEffectiveFromIT extends AbstractIntegrationTest {

    @Autowired
    OfferCatalogue offers;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seededVersionOneCarriesTheBackfilledEffectiveFrom() {
        // The V17 publish (2026-01-01 00:00:00+00) is backfilled by V53 with the UTC date of published_at.
        Offer offer = offers.offer("BASIC").orElseThrow();

        assertThat(offer.version(1).orElseThrow().effectiveFrom()).isEqualTo(LocalDate.parse("2026-01-01"));
    }

    @Test
    void aVersionTakesEffectOnItsDate() {
        jdbc.update("INSERT INTO offers.offer_version (offer_id, version, kind, free_periods, published_at, effective_from)"
                + " VALUES ('BASIC', 2, 'fixed-price-phase', 0, TIMESTAMPTZ '2026-10-20 10:00:00+00', DATE '2026-11-01')");
        jdbc.update("INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)"
                + " VALUES ('BASIC', 2, 'USD', 1099)");

        Offer offer = offers.offer("BASIC").orElseThrow();

        assertThat(offer.currentOn(LocalDate.parse("2026-10-20")).map(v -> v.number())).contains(1);
        assertThat(offer.currentOn(LocalDate.parse("2026-10-31")).map(v -> v.number())).contains(1);
        assertThat(offer.currentOn(LocalDate.parse("2026-11-01")).map(v -> v.number())).contains(2);

        OfferVersion versionOne = offer.version(1).orElseThrow();
        assertThat(versionOne.terms().priceIn(Currency.getInstance("USD")))
                .contains(Money.of(999, "USD"));
        assertThat(offer.version(2).orElseThrow().terms().priceIn(Currency.getInstance("USD")))
                .contains(Money.of(1099, "USD"));
    }
}
