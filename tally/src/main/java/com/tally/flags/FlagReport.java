package com.tally.flags;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The flags report (ADR-053, ADR-010): the flags sorted by name, and the names of the flags that
 * are overdue, sorted. A removed flag is not listed.
 */
public record FlagReport(Instant asOf, List<FlagView> flags, List<String> overdue) {

    public FlagReport {
        Objects.requireNonNull(asOf, "asOf");
        List<FlagView> sortedFlags = new ArrayList<>(Objects.requireNonNull(flags, "flags"));
        sortedFlags.sort(Comparator.comparing(FlagView::name));
        flags = List.copyOf(sortedFlags);
        List<String> sortedOverdue = new ArrayList<>(Objects.requireNonNull(overdue, "overdue"));
        sortedOverdue.sort(String::compareTo);
        overdue = List.copyOf(sortedOverdue);
    }
}
