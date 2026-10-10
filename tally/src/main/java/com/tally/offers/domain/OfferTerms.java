package com.tally.offers.domain;

import com.tally.shared.Money;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;

/**
 * What a published offer version says, read by the pricing rule of its kind: the price of one period
 * in each currency it is sold in, and how many periods are free at the start (free-trial kind only).
 */
public record OfferTerms(Map<Currency, Money> pricePerPeriod, int freePeriods) {

    public OfferTerms {
        pricePerPeriod = Map.copyOf(pricePerPeriod);
        pricePerPeriod.forEach((currency, price) -> {
            if (!price.currency().equals(currency)) {
                throw new IllegalArgumentException("The " + currency + " price is in " + price.currency());
            }
        });
        if (freePeriods < 0) {
            throw new IllegalArgumentException("Free periods cannot be negative: " + freePeriods);
        }
    }

    public Optional<Money> priceIn(Currency currency) {
        return Optional.ofNullable(pricePerPeriod.get(currency));
    }
}
