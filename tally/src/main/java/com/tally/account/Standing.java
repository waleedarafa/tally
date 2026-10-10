package com.tally.account;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** An account's standing (design.md §4): only Collections changes it, through {@link AccountStanding} (F3). */
public enum Standing {
    GOOD,
    OVERDUE,
    BLOCKED,
    /** A value this release does not know (CLAUDE.md rule 7). */
    @JsonEnumDefaultValue
    UNKNOWN
}
