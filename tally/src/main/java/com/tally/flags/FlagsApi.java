package com.tally.flags;

import java.util.Map;
import java.util.UUID;

/** Public read API of the flags module (ADR-010, ADR-048): evaluates every defined flag for an account and country. */
public interface FlagsApi {

    /** Effective state of every defined flag for {@code accountId} in {@code country}, keyed by flag name. */
    Map<String, Boolean> statesFor(UUID accountId, String country);
}
