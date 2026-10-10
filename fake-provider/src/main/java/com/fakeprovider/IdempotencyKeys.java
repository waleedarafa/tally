package com.fakeprovider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The provider's idempotency layer, modelled on Stripe's API v1 (CONTRACT.md A8, A22, A23): the first
 * response for a key is saved once the charge has executed and replayed for every repeat within the
 * retention period, whatever the outcome, declines included. A repeat with other parameters is refused,
 * and so is a repeat while the first request still executes; neither is saved.
 */
@Component
class IdempotencyKeys {

    /** Stripe prunes keys after at least 24 hours; after that a reused key makes a new request. */
    static final Duration RETENTION = Duration.ofHours(24);

    sealed interface Entry permits Executing, Saved {
    }

    record Executing(Object request) implements Entry {
    }

    record Saved(Object request, Object response, Instant savedAt) implements Entry {
    }

    /** What a request with a key must do. */
    sealed interface Decision permits Execute, Replay, Conflict, Mismatch {
    }

    record Execute() implements Decision {
    }

    record Replay(Object response) implements Decision {
    }

    /** Another request with this key is still executing (Stripe: 409). */
    record Conflict() implements Decision {
    }

    /** The key was used with other parameters (Stripe: 400, idempotency_error). */
    record Mismatch() implements Decision {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Claims the key for this request, or says why not. */
    Decision begin(String key, Object request) {
        Entry fresh = new Executing(request);
        Entry existing = entries.compute(key, (k, current) ->
                current == null || expired(current) ? fresh : current);
        if (existing == fresh) {
            return new Execute();
        }
        return switch (existing) {
            case Executing executing -> executing.request().equals(request) ? new Conflict() : new Mismatch();
            case Saved saved -> saved.request().equals(request) ? new Replay(saved.response()) : new Mismatch();
        };
    }

    void save(String key, Object request, Object response) {
        entries.put(key, new Saved(request, response, Instant.now()));
    }

    /** Test-only: as if the retention period had passed for every key. */
    void pruneAll() {
        entries.clear();
    }

    private static boolean expired(Entry entry) {
        return entry instanceof Saved saved && saved.savedAt().plus(RETENTION).isBefore(Instant.now());
    }
}
