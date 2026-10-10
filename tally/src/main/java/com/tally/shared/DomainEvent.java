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

    /**
     * The aggregate's version after the change this event reports: its sequence number (ADR-016). A
     * consumer can order the events of one aggregate by it, and tell a stale one from a newer one.
     * Versions may skip numbers: not every change publishes an event. Null in an event published before
     * Slice 1R PR-7, which had no sequence: a boxed type, because Jackson 3 refuses a missing primitive
     * ({@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by default), so a primitive would not be an added field.
     */
    Long sequence();
}
