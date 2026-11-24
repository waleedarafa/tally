package com.tally.flags.domain;

/** The flags catalogue (a port; {@code adapter.persistence} implements it, F9, ADR-049). */
public interface FlagCatalogue {

    /** The full catalogue as currently stored. */
    Flags load();

    /** Replaces the stored catalogue with this snapshot, in one transaction. */
    void save(Flags flags);
}
