package com.tally.offers.application;

import com.tally.offers.domain.FixedPricePhaseRule;
import com.tally.offers.domain.FreeTrialRule;
import com.tally.offers.domain.PricingRule;
import com.tally.offers.domain.PricingRuleRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The pricing rules, one bean per kind of offer, and their registry (ADR-015). A new kind adds a bean here. */
@Configuration(proxyBeanMethods = false)
class PricingConfiguration {

    @Bean
    FixedPricePhaseRule fixedPricePhaseRule() {
        return new FixedPricePhaseRule();
    }

    @Bean
    FreeTrialRule freeTrialRule() {
        return new FreeTrialRule();
    }

    @Bean
    PricingRuleRegistry pricingRuleRegistry(List<PricingRule> rules) {
        return new PricingRuleRegistry(rules);
    }
}
