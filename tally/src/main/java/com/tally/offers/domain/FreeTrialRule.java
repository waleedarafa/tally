package com.tally.offers.domain;

import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.shared.Money;
import java.util.List;

/**
 * Kind {@code free-trial}: the first {@link OfferTerms#freePeriods()} periods are free, then each costs the
 * price. A free period shows the price and a credit of the same amount, so the invoice says what the trial
 * is worth and its total is zero.
 */
public final class FreeTrialRule implements PricingRule {

    public static final String KIND = "free-trial";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public List<PriceLine> lines(OfferTerms terms, Money pricePerPeriod, Period period) {
        PriceLine price = PricingRule.periodLine(period, pricePerPeriod);
        if (period.number() > terms.freePeriods()) {
            return List.of(price);
        }
        Money credit = new Money(0, pricePerPeriod.currency()).minus(pricePerPeriod);
        return List.of(price, new PriceLine(
                "Free trial, period " + period.number() + " of " + terms.freePeriods(), credit));
    }
}
