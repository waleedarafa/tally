package com.tally.offers;

import com.tally.shared.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Offers and Pricing's published language (design.md §4): what one subscription owes for one period on
 * one offer version. The total is the sum of the lines, never rounded again (ADR-017).
 */
public record PriceQuote(UUID subscriptionId, String offerId, int offerVersion, Period period,
        List<PriceLine> lines, Money total, QuoteReason reason) {

    public PriceQuote {
        Objects.requireNonNull(reason, "reason");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A quote has at least one line");
        }
        Money sum = lines.stream().map(PriceLine::amount).reduce(Money::plus).orElseThrow();
        if (!sum.equals(total)) {
            throw new IllegalArgumentException("The total " + total + " is not the sum of the lines " + sum);
        }
    }

    /** Sums the lines into the total. */
    public static PriceQuote of(QuoteRequest request, int offerVersion, List<PriceLine> lines, QuoteReason reason) {
        Money total = lines.stream().map(PriceLine::amount).reduce(Money::plus)
                .orElseThrow(() -> new IllegalArgumentException("A quote has at least one line"));
        return new PriceQuote(request.subscriptionId(), request.offerId(), offerVersion, request.period(), lines,
                total, reason);
    }
}
