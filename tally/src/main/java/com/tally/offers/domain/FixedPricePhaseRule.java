package com.tally.offers.domain;

import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.shared.Money;
import java.util.List;

/** Kind {@code fixed-price-phase}: every period costs the same price. The first run's plans are this kind. */
public final class FixedPricePhaseRule implements PricingRule {

    public static final String KIND = "fixed-price-phase";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public List<PriceLine> lines(OfferTerms terms, Money pricePerPeriod, Period period) {
        return List.of(PricingRule.periodLine(period, pricePerPeriod));
    }
}
