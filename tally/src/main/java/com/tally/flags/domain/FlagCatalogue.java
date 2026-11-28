package com.tally.flags.domain;

/** The flags catalogue (a port; {@code adapter.persistence} implements it, F9, ADR-049). */
public interface FlagCatalogue {

    /** The full catalogue as currently stored. */
    Flags load();

    /** Like {@link #load()}, but first takes a lock on the catalogue that is held to the end of the current transaction, so a second change waits for the first and then sees its result (ADR-053). Must be called inside a transaction. */
    Flags loadForUpdate();

    /** Replaces the stored catalogue with this snapshot, in one transaction. */
    void save(Flags flags);
}
