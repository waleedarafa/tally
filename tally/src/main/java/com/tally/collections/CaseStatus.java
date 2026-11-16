package com.tally.collections;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * A Collections case's state (design.md §5). Slice 1R builds OPEN and CLOSED; Slice 3 adds OVERDUE, BLOCKED and
 * WRITTEN_OFF (add only, CLAUDE.md rule 7). Every state but CLOSED has a debt open.
 */
public enum CaseStatus {
    OPEN,
    /** None of the case's invoices is unpaid. */
    CLOSED,
    /** One hour after retry 2 was asked, a debt still open. The standing is OVERDUE. */
    OVERDUE,
    /** Day 10, a debt still open. The standing is BLOCKED. */
    BLOCKED,
    /** Day 30, a debt still open: the subscription ended. The standing stays BLOCKED until the debt is paid. */
    WRITTEN_OFF,
    /** A value this release does not know (CLAUDE.md rule 7). */
    @JsonEnumDefaultValue
    UNKNOWN
}
