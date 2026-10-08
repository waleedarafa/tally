package com.tally.payment;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Why a payment failed. Stored in {@link PaymentFailed}. UNKNOWN is also the default for a reason
 * this version does not know, such as a reason a newer release adds (CLAUDE.md rule 7; D15). Its
 * consumers treat UNKNOWN as unresolved and take no action on it (ADR-018).
 */
public enum FailureReason {
    SOFT, HARD,
    @JsonEnumDefaultValue
    UNKNOWN
}
