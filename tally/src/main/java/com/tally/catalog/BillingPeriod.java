package com.tally.catalog;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Slice 1 sells monthly plans only. Crosses module boundaries ({@link CatalogApi}), so it has an
 * {@code UNKNOWN} default for values this version does not know (CLAUDE.md rule 7). No plan has an
 * {@code UNKNOWN} price, so a quote for it finds none.
 */
public enum BillingPeriod {
    MONTHLY,
    @JsonEnumDefaultValue
    UNKNOWN
}
