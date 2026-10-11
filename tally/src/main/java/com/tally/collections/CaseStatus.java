package com.tally.collections;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** A Collections case's state (design.md §5). Slice 1R builds OPEN and CLOSED. */
public enum CaseStatus {
    OPEN,
    /** The invoice was paid. */
    CLOSED,
    /** A value this release does not know (CLAUDE.md rule 7). */
    @JsonEnumDefaultValue
    UNKNOWN
}
