package com.tally.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PricingRuleRegistryTest {

    final PricingRuleRegistry registry = new PricingRuleRegistry(List.of(new FixedPricePhaseRule(), new FreeTrialRule()));

    @Test
    void findsTheRuleForEachKind() {
        assertThat(registry.ruleFor("fixed-price-phase")).isInstanceOf(FixedPricePhaseRule.class);
        assertThat(registry.ruleFor("free-trial")).isInstanceOf(FreeTrialRule.class);
        assertThatThrownBy(() -> registry.ruleFor("bundle")).isInstanceOf(IllegalStateException.class);
    }

    /** The start-up check (ADR-015): only an approved kind must have a rule. */
    @Test
    void namesTheApprovedKindsThatHaveNoRule() {
        OfferKind bundle = OfferKind.experimental("bundle");
        OfferKind family = OfferKind.experimental("family");
        family.approve();

        assertThat(registry.approvedWithoutRule(List.of(OfferTest.approved("fixed-price-phase"), bundle, family)))
                .containsExactly("family");
    }

    @Test
    void oneRulePerKind() {
        assertThatThrownBy(() -> new PricingRuleRegistry(List.of(new FreeTrialRule(), new FreeTrialRule())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
