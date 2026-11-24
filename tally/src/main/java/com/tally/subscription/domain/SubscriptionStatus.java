package com.tally.subscription.domain;

/**
 * A subscription's state (design.md §5; ADR-018). The transitions live in {@link Subscription}'s
 * transition table: TRIAL → ACTIVE (trial ends) · TRIAL or ACTIVE → CANCELLING (cancel) · ACTIVE → ACTIVE
 * (renew(n) for the current period only) · ACTIVE → ENDED (late cancel requested before period end, a
 * renewal reversed) · CANCELLING → ENDED (period ends) · any live state → ENDED (write-off) · ENDED
 * refuses everything.
 */
public enum SubscriptionStatus {
    /** The offer is free; the trial ends into ACTIVE. */
    TRIAL,
    ACTIVE,
    /** The household asked to cancel; access ends when the current period ends. */
    CANCELLING,
    ENDED
}
