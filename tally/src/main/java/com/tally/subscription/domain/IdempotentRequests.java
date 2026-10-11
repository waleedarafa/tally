package com.tally.subscription.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * REST requests by their client's {@code Idempotency-Key} (ADR-019, CLAUDE.md rule 13): the key is stored
 * before acting, then the response with it, and kept for {@code keptFor}. A port; {@code adapter.persistence}
 * implements it.
 */
public interface IdempotentRequests {

    /** What a key's stored state says to do with a request. */
    sealed interface Claim {
    }

    /** The key is new, or older than its keep time: act, then {@link #complete} or {@link #release}. */
    record Act() implements Claim {
    }

    /** The same request already has a response: return it, as stored. */
    record Replay(int status, String contentType, String body) implements Claim {
    }

    /** The key was used for a different request. */
    record Reused() implements Claim {
    }

    /** The same request is still being handled. */
    record InProgress() implements Claim {
    }

    /**
     * Claims {@code key} for the request whose fingerprint is {@code requestHash}, atomically: of two
     * concurrent claims of a new key, one acts and the other is told it is in progress.
     */
    Claim claim(String key, String requestHash, Instant now, Duration keptFor);

    /** Stores the response the key replays from now on. */
    void complete(String key, int status, String contentType, String body);

    /** Forgets a key whose request failed unexpectedly, so the client may try again with it. */
    void release(String key);
}
