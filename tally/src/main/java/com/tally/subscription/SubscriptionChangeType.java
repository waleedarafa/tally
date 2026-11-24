package com.tally.subscription;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Kinds of subscription change (design.md §4), mapped onto the one event class (ADR-016: types are added,
 * never renamed or removed). Stored in {@link SubscriptionChanged}, so a change type a newer release adds
 * reads as UNKNOWN here (CLAUDE.md rule 7); UNKNOWN is never produced, and consumers ignore it. Plan changes
 * and reversals are added when their behaviour lands.
 */
public enum SubscriptionChangeType {
    STARTED,
    /** The subscription moved into its next period (Slice 1R PR-5). */
    PERIOD_RENEWED,
    /** The household asked to cancel: access ends when the current period ends (design.md §5). */
    CANCELLED,
    /** The subscription ended, and will never renew (Slice 3, design.md §5). */
    ENDED,
    @JsonEnumDefaultValue
    UNKNOWN
}
