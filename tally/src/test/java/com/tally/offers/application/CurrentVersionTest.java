package com.tally.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.account.UnknownAccountException;
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
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The version in force on a date (ADR-047): {@code Offers.currentVersion} must use the account's
 * local date — the clock instant in the account's time zone — not the latest published row.
 * Each test gets its own ports, clock and offer, so tests never depend on each other's state.
 */
class CurrentVersionTest {

    final InMemoryOffers ports = new InMemoryOffers();
    final TestClock clock = new TestClock();
    final PricingService pricing = new PricingService(ports, ports,
            new PricingRuleRegistry(List.of(new FixedPricePhaseRule(), new FreeTrialRule())), clock);

    static OfferKind approved() {
        OfferKind kind = OfferKind.experimental("fixed-price-phase");
        kind.approve();
        return kind;
    }

    static OfferTerms usd(long minorUnits) {
        return new OfferTerms(Map.of(Currency.getInstance("USD"), Money.of(minorUnits, "USD")), 0);
    }

    /** The golden A-06 catalogue: v1 at 9.99 USD effective 2026-01-01, v2 at 10.99 USD effective 2026-11-01. */
    Offer basicCatalogue() {
        OfferKind approved = approved();
        Offer offer = Offer.create("BASIC", "BASIC");
        offer.publish(approved, usd(999), Instant.parse("2026-01-01T00:00:00Z"), LocalDate.parse("2026-01-01"));
        offer.publish(approved, usd(1099), Instant.parse("2026-10-20T10:00:00Z"), LocalDate.parse("2026-11-01"));
        ports.offers.put("BASIC", offer);
        return offer;
    }

    UUID account(Currency currency, ZoneId zone) {
        return ports.open(currency, zone).id();
    }

    @Test
    void goldenA06AVersionAppliesOnItsEffectiveFrom() {
        basicCatalogue();
        UUID account = account(Currency.getInstance("USD"), ZoneId.of("UTC"));

        clock.set(Instant.parse("2026-10-20T10:00:00Z"));
        assertThat(pricing.currentVersion("BASIC", account)).isEqualTo(OptionalInt.of(1));

        clock.set(Instant.parse("2026-10-31T10:00:00Z"));
        assertThat(pricing.currentVersion("BASIC", account)).isEqualTo(OptionalInt.of(1));

        clock.set(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(pricing.currentVersion("BASIC", account)).isEqualTo(OptionalInt.of(2));
    }

    @Test
    void midnightSplitsAcrossZones() {
        // The same clock instant: Dubai is already on 2026-11-01, UTC is still on 2026-10-31.
        basicCatalogue();
        UUID dubai = account(Currency.getInstance("USD"), ZoneId.of("Asia/Dubai"));
        UUID utc = account(Currency.getInstance("USD"), ZoneId.of("UTC"));

        clock.set(Instant.parse("2026-10-31T20:00:00Z"));

        assertThat(pricing.currentVersion("BASIC", dubai)).isEqualTo(OptionalInt.of(2));
        assertThat(pricing.currentVersion("BASIC", utc)).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void aUsdOnlyOfferIsEmptyForAEurAccountEvenInForce() {
        Offer offer = Offer.create("USD-ONLY", "USD-ONLY");
        offer.publish(approved(), usd(999), Instant.parse("2026-01-01T00:00:00Z"), LocalDate.parse("2026-01-01"));
        ports.offers.put("USD-ONLY", offer);
        UUID eur = account(Currency.getInstance("EUR"), ZoneId.of("UTC"));
        UUID usdAccount = account(Currency.getInstance("USD"), ZoneId.of("UTC"));

        clock.set(Instant.parse("2026-10-20T10:00:00Z"));

        assertThat(pricing.currentVersion("USD-ONLY", eur)).isEqualTo(OptionalInt.empty());
        assertThat(pricing.currentVersion("USD-ONLY", usdAccount)).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void onlyAFutureVersionIsEmptyAndNeverFallsBackToOfferCurrent() {
        Offer offer = Offer.create("FUTURE", "FUTURE");
        offer.publish(approved(), usd(1099), Instant.parse("2026-10-20T10:00:00Z"), LocalDate.parse("2026-11-01"));
        ports.offers.put("FUTURE", offer);
        UUID account = account(Currency.getInstance("USD"), ZoneId.of("UTC"));

        clock.set(Instant.parse("2026-10-20T10:00:00Z"));

        assertThat(offer.current()).isPresent(); // the latest published row is in force-and-future, but
        assertThat(pricing.currentVersion("FUTURE", account)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void anUnknownAccountIsRefusedAndAnUnknownOfferIsEmpty() {
        basicCatalogue();
        UUID account = account(Currency.getInstance("USD"), ZoneId.of("UTC"));
        clock.set(Instant.parse("2026-10-20T10:00:00Z"));

        assertThatThrownBy(() -> pricing.currentVersion("BASIC", UUID.randomUUID()))
                .isInstanceOf(UnknownAccountException.class);
        assertThat(pricing.currentVersion("NOPE", account)).isEqualTo(OptionalInt.empty());
    }
}
