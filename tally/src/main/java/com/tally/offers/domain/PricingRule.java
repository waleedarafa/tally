package com.tally.offers.domain;

import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.shared.Money;
import java.util.List;

/**
 * How one kind of offer is priced (ADR-015): the extension point for a new kind of offer (D5). Pure: the
 * same terms, price and period always give the same lines (O3). One rule per kind, found by
 * {@link PricingRuleRegistry}.
 */
public interface PricingRule {

    /** The kind of offer this rule prices, as {@link OfferKind#id()}. */
    String kind();

    /** The lines for one period, given the version's terms and its price in the account's currency. */
    List<PriceLine> lines(OfferTerms terms, Money pricePerPeriod, Period period);

    /** The line every rule starts from: the period and its price. */
    static PriceLine periodLine(Period period, Money price) {
        return new PriceLine("Period " + period.number() + ", " + period.start() + " to " + period.end(), price);
    }
}
