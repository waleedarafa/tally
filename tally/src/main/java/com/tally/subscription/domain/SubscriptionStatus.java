package com.tally.subscription.domain;

/** A subscription's state (design.md §5). Slice 3 builds ACTIVE and ENDED only; ENDED refuses everything. */
public enum SubscriptionStatus {
    ACTIVE,
    ENDED
}
