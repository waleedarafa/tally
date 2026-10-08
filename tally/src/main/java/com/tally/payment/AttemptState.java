package com.tally.payment;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * States of a payment attempt (brief section 1). UNKNOWN outcomes are reconciled, never retried
 * (ADR-018). UNKNOWN is also the default for a value this version does not know (CLAUDE.md rule 7):
 * "the charge may have happened" is the safe reading of a state it cannot interpret.
 */
public enum AttemptState {
    PENDING, IN_FLIGHT, SUCCEEDED, FAILED,
    @JsonEnumDefaultValue
    UNKNOWN
}
