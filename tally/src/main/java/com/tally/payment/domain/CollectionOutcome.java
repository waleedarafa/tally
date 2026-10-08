package com.tally.payment.domain;

/** A collection request's overall outcome (ADR-041). Its attempts hold the outcome of each try. */
public enum CollectionOutcome {
    /** No attempt has resolved yet, or the latest attempt's outcome is unknown. */
    OPEN,
    SUCCEEDED,
    FAILED
}
