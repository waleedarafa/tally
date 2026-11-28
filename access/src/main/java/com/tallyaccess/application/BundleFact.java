package com.tallyaccess.application;

import java.time.Instant;
import java.util.UUID;

/** A partner bundle, in Access's own words (ADR-056). */
public record BundleFact(String partnerId, String bundleId, UUID subscriberId,
        State state, long partnerSequence, Instant occurredAt) {

    public enum State {
        ACTIVE,
        ENDED
    }
}
