package com.tally.offers;

import java.util.Map;
import java.util.TreeMap;

/**
 * Why a quote is what it is (D7, ADR-010): the offer and version priced, the kind whose rule priced
 * it, and the state of every defined feature flag for the account at quote time.
 */
public record QuoteReason(String offerId, int offerVersion, String kind, Map<String, Boolean> flags) {

    public QuoteReason {
        flags = Map.copyOf(flags);
    }

    /** One line for an auditor, stored on the invoice. Flags are listed in name order. */
    public String summary() {
        return "offer " + offerId + " version " + offerVersion + ", kind " + kind + ", flags "
                + (flags.isEmpty() ? "none" : new TreeMap<>(flags).toString());
    }
}
