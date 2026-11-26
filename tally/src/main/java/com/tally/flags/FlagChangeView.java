package com.tally.flags;

import java.time.Instant;

/** One entry in a flag's change log, as the admin API shows it (ADR-053). */
public record FlagChangeView(Instant at, String description) {
}
