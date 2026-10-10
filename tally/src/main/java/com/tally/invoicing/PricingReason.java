package com.tally.invoicing;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Why an invoice is priced as it is (D7, ADR-010): the offer and version its PriceQuote priced, the kind
 * whose rule priced it, and every feature flag that took part. Stored as data (jsonb), so the flag report
 * can find every invoice priced under a flag. {@link #summary()} is for display only.
 */
public record PricingReason(String offerId, int offerVersion, String kind, Map<String, Boolean> flags) {

    public PricingReason {
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(kind, "kind");
        flags = Map.copyOf(flags);
    }

    /** One line for a person. Flags in name order. */
    public String summary() {
        return "offer " + offerId + " version " + offerVersion + ", kind " + kind + ", flags "
                + (flags.isEmpty() ? "none" : new TreeMap<>(flags).toString());
    }
}
