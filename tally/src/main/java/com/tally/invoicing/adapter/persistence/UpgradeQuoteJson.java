package com.tally.invoicing.adapter.persistence;

import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteReason;
import com.tally.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Stores a {@link PriceQuote} as JSON in {@code invoicing.upgrade_intent.quote} (jsonb). */
final class UpgradeQuoteJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private UpgradeQuoteJson() {
    }

    static String toJson(PriceQuote quote) {
        return JSON.writeValueAsString(StoredQuote.from(quote));
    }

    static PriceQuote fromJson(String json) {
        return JSON.readValue(json, StoredQuote.class).toQuote();
    }

    record StoredLine(String description, long amountMinor, String currency) {
        static StoredLine from(PriceLine line) {
            return new StoredLine(line.description(), line.amount().minorUnits(),
                    line.amount().currency().getCurrencyCode());
        }

        PriceLine toLine() {
            return new PriceLine(description, Money.of(amountMinor, currency));
        }
    }

    record StoredQuote(UUID subscriptionId, String offerId, int offerVersion, int periodNumber, String periodStart,
            String periodEnd, List<StoredLine> lines, long totalMinor, String currency, String reasonOfferId,
            int reasonOfferVersion, String reasonKind, Map<String, Boolean> flags) {

        static StoredQuote from(PriceQuote quote) {
            return new StoredQuote(quote.subscriptionId(), quote.offerId(), quote.offerVersion(),
                    quote.period().number(), quote.period().start().toString(), quote.period().end().toString(),
                    quote.lines().stream().map(StoredLine::from).toList(), quote.total().minorUnits(),
                    quote.total().currency().getCurrencyCode(), quote.reason().offerId(), quote.reason().offerVersion(),
                    quote.reason().kind(), quote.reason().flags());
        }

        PriceQuote toQuote() {
            return new PriceQuote(subscriptionId, offerId, offerVersion,
                    new Period(periodNumber, LocalDate.parse(periodStart), LocalDate.parse(periodEnd)),
                    lines.stream().map(StoredLine::toLine).toList(), Money.of(totalMinor, currency),
                    new QuoteReason(reasonOfferId, reasonOfferVersion, reasonKind, flags == null ? Map.of() : flags));
        }
    }
}
