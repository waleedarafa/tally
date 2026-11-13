package com.tally.accessfeed.domain;

import java.util.UUID;

/**
 * One source of an account's facts.
 *
 * @param sourceSequence the source's own sequence in the last event applied; null when that event had none
 */
public record Source(SourceKind kind, UUID id, SourceState state, Long sourceSequence) {
}
