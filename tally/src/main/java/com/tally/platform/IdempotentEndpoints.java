package com.tally.platform;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The HTTP side of idempotency keys (ADR-019, CLAUDE.md rule 13), shared by every endpoint that changes state.
 * Errors are RFC 9457 problems written out as JSON, so a stored one replays byte for byte.
 */
public interface IdempotentEndpoints {

    /**
     * Handles one request that changes state:
     * <ul>
     *   <li>no {@code Idempotency-Key}, or one that is not a UUID: 400 problem, not stored;</li>
     *   <li>a key used for a different request: 422 {@code /problems/idempotency-key-reused};</li>
     *   <li>the same request still being handled: 409 {@code /problems/request-in-progress}, with
     *       {@code Retry-After} the seconds left of its request timeout;</li>
     *   <li>the same request already answered: the stored response;</li>
     *   <li>otherwise {@code act}, and its response stored, in one transaction. An exception {@code refusals}
     *       maps to a response rolls {@code act} back; that response is then stored on its own. Any other
     *       exception releases the key and is rethrown.</li>
     * </ul>
     *
     * @param scope   who the key belongs to: the account, until callers are authenticated
     * @param request the request body; its JSON is the request's fingerprint
     */
    ResponseEntity<String> handle(IdempotencyStore store, String scope, String key, Object request,
            Supplier<ResponseEntity<String>> act, Function<RuntimeException, Optional<ResponseEntity<String>>> refusals);

    /** An RFC 9457 problem, {@code application/problem+json}. */
    ResponseEntity<String> problem(HttpStatus status, String type, String title, String detail);
}
