package com.tally.subscription;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Kinds of subscription change. Slice 1 only starts subscriptions; values are only ever added.
 * Stored in {@link SubscriptionChanged}, so a change type a newer release adds reads as UNKNOWN
 * here (CLAUDE.md rule 7). Never produced by this version; consumers ignore it.
 */
public enum SubscriptionChangeType {
    STARTED,
    @JsonEnumDefaultValue
    UNKNOWN
}
