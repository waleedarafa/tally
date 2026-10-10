package com.tally.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope every Tally event carries (FR-009). Implementations are pinned by name:
 * an event class is never renamed or moved, because Spring Modulith stores the class
 * name with each pending publication.
 */
public interface DomainEvent {

    UUID eventId();

    /** When the fact happened, taken from the injected clock. */
    Instant occurredAt();

    UUID accountId();
}
