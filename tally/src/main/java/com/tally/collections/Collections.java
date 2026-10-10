package com.tally.collections;

import java.util.Optional;
import java.util.UUID;

/** Collections' public API (design.md §4). */
public interface Collections {

    /** The account's case: the open one, or else the latest. Empty if no payment was ever declined. */
    Optional<CaseView> caseFor(UUID accountId);
}
