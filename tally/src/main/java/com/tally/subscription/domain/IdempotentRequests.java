package com.tally.subscription.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * REST requests by their client's {@code Idempotency-Key} (ADR-019, CLAUDE.md rule 13), scoped by account
 * until callers are authenticated: the key is claimed before acting; the response is stored in the same
 * transaction as the request's business change; both are kept for {@code keptFor}. A port;
 * {@code adapter.persistence} implements it.
 */
public interface IdempotentRequests {

    /** What a key's stored state says to do with a request. */
    sealed interface Claim {
    }

    /**
     * Act, then {@link #complete} in the same transaction as the business change. The key is new, older than
     * its keep time, or held by a request that started longer ago than the request timeout: that one died.
     */
    record Act() implements Claim {
    }

    /** The same request already has a response: return it, as stored. */
    record Replay(int status, String contentType, String body) implements Claim {
    }

    /** The key was used for a different request. */
    record Reused() implements Claim {
    }

    /** The same request is still being handled, within the request timeout. */
    record InProgress() implements Claim {
    }

    /**
     * Claims the account's {@code key} for the request whose fingerprint is {@code requestHash}, in a
     * transaction of its own, atomically: of two concurrent claims, one acts and the other is told the
     * request is in progress.
     */
    Claim claim(UUID accountId, String key, String requestHash, Instant now, Duration keptFor,
            Duration requestTimeout);

    /** Stores the response the key replays from now on. Joins the caller's transaction. */
    void complete(UUID accountId, String key, int status, String contentType, String body);

    /** Forgets a key whose request failed unexpectedly, so the client may try again with it at once. */
    void release(UUID accountId, String key);
}
