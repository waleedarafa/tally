package com.tallyaccess.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The answer to "can I watch?" (design.md §8; docs/api/access-openapi.yaml).
 *
 * @param asOf     the {@code producedAt} of the facts behind the answer; null for NOT_KNOWN
 * @param sequence the sequence of those facts; null for NOT_KNOWN
 */
public record AccessAnswer(UUID subscriberId, boolean canWatch, Reason reason, Instant asOf, Long sequence) {

    public enum Reason {
        ACTIVE_SOURCE,
        ACCOUNT_BLOCKED,
        NO_ACTIVE_SOURCE,
        NOT_KNOWN
    }
}
