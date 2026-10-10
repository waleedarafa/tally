package com.tally.offers.application;

import com.tally.offers.domain.OfferKinds;
import com.tally.offers.domain.PricingRuleRegistry;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * ADR-015's start-up check: every approved kind of offer has a pricing rule, or Tally refuses to start.
 * Runs once every bean exists; an exception here fails the application context.
 */
@Component
class PricingRulesCheck implements SmartInitializingSingleton {

    private final OfferKinds kinds;
    private final PricingRuleRegistry rules;

    PricingRulesCheck(OfferKinds kinds, PricingRuleRegistry rules) {
        this.kinds = kinds;
        this.rules = rules;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = rules.approvedWithoutRule(kinds.all());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Approved offer kinds with no pricing rule: " + missing
                    + ". Tally refuses to start (ADR-015).");
        }
    }
}
