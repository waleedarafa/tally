package com.tally.offers.adapter.persistence;

import com.tally.offers.domain.KindStatus;
import com.tally.offers.domain.Offer;
import com.tally.offers.domain.OfferCatalogue;
import com.tally.offers.domain.OfferKind;
import com.tally.offers.domain.OfferKinds;
import com.tally.offers.domain.OfferTerms;
import com.tally.offers.domain.OfferVersion;
import com.tally.shared.Money;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Offers, their versions and prices, and the kinds of offer, from the {@code offers} schema. */
@Repository
class JdbcOffers implements OfferCatalogue, OfferKinds {

    private final JdbcTemplate jdbc;

    JdbcOffers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Offer> offer(String offerId) {
        List<String> names = jdbc.queryForList("SELECT name FROM offers.offer WHERE id = ?", String.class, offerId);
        if (names.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, Map<Currency, Money>> prices = new HashMap<>();
        jdbc.query("SELECT version, currency, amount_minor FROM offers.offer_version_price WHERE offer_id = ?",
                rs -> {
                    Currency currency = Currency.getInstance(rs.getString("currency"));
                    prices.computeIfAbsent(rs.getInt("version"), v -> new HashMap<>())
                            .put(currency, new Money(rs.getLong("amount_minor"), currency));
                }, offerId);
        List<OfferVersion> versions = jdbc.query(
                "SELECT version, kind, free_periods, published_at FROM offers.offer_version"
                        + " WHERE offer_id = ? ORDER BY version",
                (rs, n) -> new OfferVersion(rs.getInt("version"), rs.getString("kind"),
                        new OfferTerms(prices.getOrDefault(rs.getInt("version"), Map.of()), rs.getInt("free_periods")),
                        rs.getTimestamp("published_at").toInstant()),
                offerId);
        return Optional.of(Offer.of(offerId, names.getFirst(), versions));
    }

    @Override
    public List<OfferKind> all() {
        return jdbc.query("SELECT id, status FROM offers.offer_kind ORDER BY id",
                (rs, n) -> OfferKind.of(rs.getString("id"), KindStatus.valueOf(rs.getString("status"))));
    }
}
