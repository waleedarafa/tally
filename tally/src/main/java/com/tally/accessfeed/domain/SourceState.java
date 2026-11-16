package com.tally.accessfeed.domain;

/**
 * A source's state in the facts (design.md §4, AccessFacts). Later slices add states (ADR-016: add only).
 *
 * <p>ENDED is the feed's own: an ended source is not in the message (it has no sources then), and its last sequence
 * is kept, so an older event of that source never brings it back (CLAUDE.md rule 7).
 */
public enum SourceState {
    ACTIVE,
    ENDED
}
