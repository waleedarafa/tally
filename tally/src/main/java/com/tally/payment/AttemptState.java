package com.tally.payment;

/** States of a payment attempt (brief section 1). UNKNOWN outcomes are reconciled, never retried (ADR-018). */
public enum AttemptState {
    PENDING, IN_FLIGHT, SUCCEEDED, FAILED, UNKNOWN
}
