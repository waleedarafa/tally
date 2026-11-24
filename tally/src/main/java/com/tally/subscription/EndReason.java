package com.tally.subscription;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Why a subscription ended (design.md §5). Collections is the caller for a write-off on day 30, or a declined first
 * invoice (025); the customer cancel that ends at the period boundary (CANCELLING → ENDED) is CANCELLED. A reason a
 * newer release adds reads as UNKNOWN here (CLAUDE.md rule 7); UNKNOWN is never produced, and an end refuses it.
 */
public enum EndReason {
    WRITE_OFF,
    FIRST_PAYMENT_DECLINED,
    CANCELLED,
    @JsonEnumDefaultValue
    UNKNOWN
}
