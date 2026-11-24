package com.tally.flags.domain;

import java.time.Instant;
import java.util.Objects;

/** One entry in a flag's change log: when it happened and what was logged (ADR-010). */
public record FlagChange(Instant at, String description) {

    public FlagChange {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(description, "description");
    }
}
