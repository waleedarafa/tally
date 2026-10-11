package com.tally.platform;

import java.time.Duration;

/**
 * One module's idempotency keys (ADR-019): a key is claimed before acting; the response is stored in the same
 * transaction as the request's business change; both are kept for {@code tally.platform.idempotency.kept-for}
 * (24 hours). A key held without a response for longer than {@code request-timeout} (60 seconds) belonged to a
 * request that died: the next request with it takes it over. Most callers use {@link IdempotentEndpoints}.
 */
public interface IdempotencyStore {

    /** What a key's stored state says to do with a request. */
    sealed interface Claim {
    }

    /** Act, then {@link #complete} in the same transaction as the business change. */
    record Act() implements Claim {
    }

    /** The same request already has a response: return it, as stored. */
    record Replay(StoredResponse response) implements Claim {
    }

    /** The key was used for a different request. */
    record Reused() implements Claim {
    }

    /** The same request is still being handled; its request timeout ends in {@code retryAfter}. */
    record InProgress(Duration retryAfter) implements Claim {
    }

    /** An HTTP response, stored as sent, so a replay is byte for byte the same. */
    record StoredResponse(int status, String contentType, String body) {
    }

    /**
     * Claims {@code key} within {@code scope} for the request whose fingerprint is {@code requestHash}, in a
     * transaction of its own, atomically: of two concurrent claims, one acts and the other is told the request
     * is in progress.
     */
    Claim claim(String scope, String key, String requestHash);

    /** Stores the response the key replays from now on. Joins the caller's transaction, which must exist. */
    void complete(String scope, String key, StoredResponse response);

    /** Forgets a key whose request failed unexpectedly, so the client may try again with it at once. */
    void release(String scope, String key);
}
