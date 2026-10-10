package com.tally.offers.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Finds the pricing rule for a kind of offer (ADR-015). One rule per kind. */
public final class PricingRuleRegistry {

    private final Map<String, PricingRule> rules;

    public PricingRuleRegistry(Collection<? extends PricingRule> rules) {
        this.rules = rules.stream().collect(Collectors.toUnmodifiableMap(PricingRule::kind, Function.identity(),
                (a, b) -> {
                    throw new IllegalArgumentException("Two pricing rules for kind " + a.kind());
                }));
    }

    public PricingRule ruleFor(String kind) {
        PricingRule rule = rules.get(kind);
        if (rule == null) {
            throw new IllegalStateException("No pricing rule for kind " + kind);
        }
        return rule;
    }

    /** The approved kinds that have no rule, in name order: the start-up check refuses to start on any. */
    public List<String> approvedWithoutRule(Collection<OfferKind> kinds) {
        return kinds.stream().filter(OfferKind::approved).map(OfferKind::id)
                .filter(kind -> !rules.containsKey(kind)).sorted().toList();
    }
}
