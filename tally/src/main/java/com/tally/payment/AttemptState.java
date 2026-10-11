package com.tally.payment;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * States of a payment attempt (design.md §5; ADR-013, ADR-018). The transitions live in
 * {@code PaymentAttempt}. UNKNOWN is a real state: the charge may have happened; it is resolved by
 * asking the provider, never retried. UNRECOGNISED is the reading of a state this version does not know,
 * such as one a newer release adds (ADR-024, amends ADR-016): a reader must not take it for
 * "outcome unknown".
 */
public enum AttemptState {
    /** Written, with its key; not yet sent. */
    REQUESTED,
    /** Committed as sent, before the provider call (FR-005). */
    SENT,
    SUCCEEDED,
    /** The provider declined: the bank's answer about the card. Only a decline starts Collections. */
    DECLINED,
    /** The provider failed or refused before charging. Retried as a new attempt after back-off; no event. */
    TECHNICAL_FAILURE,
    /** The charge may have happened. Resolved by key, never retried. */
    UNKNOWN,
    /** First-run state, migrated to REQUESTED by V21; never produced. */
    @Deprecated
    PENDING,
    /** First-run state, migrated to SENT by V21; never produced. */
    @Deprecated
    IN_FLIGHT,
    /** First-run state, migrated to DECLINED by V21; never produced. */
    @Deprecated
    FAILED,
    @JsonEnumDefaultValue
    UNRECOGNISED
}
