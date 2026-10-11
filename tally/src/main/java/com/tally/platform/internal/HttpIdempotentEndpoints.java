package com.tally.platform.internal;

import com.tally.platform.IdempotencyStore;
import com.tally.platform.IdempotencyStore.Act;
import com.tally.platform.IdempotencyStore.InProgress;
import com.tally.platform.IdempotencyStore.Replay;
import com.tally.platform.IdempotencyStore.Reused;
import com.tally.platform.IdempotencyStore.StoredResponse;
import com.tally.platform.IdempotentEndpoints;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** {@link IdempotentEndpoints}: the claim, the act and its stored response, and the problems in between. */
@Component
class HttpIdempotentEndpoints implements IdempotentEndpoints {

    private final TransactionTemplate transactions;
    private final JsonMapper json;

    HttpIdempotentEndpoints(TransactionTemplate transactions, JsonMapper json) {
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public ResponseEntity<String> handle(IdempotencyStore store, String scope, String key, Object request,
            Supplier<ResponseEntity<String>> act,
            Function<RuntimeException, Optional<ResponseEntity<String>>> refusals) {
        if (key == null || key.isBlank() || key.length() > 255) {
            return problem(HttpStatus.BAD_REQUEST, "about:blank", "An Idempotency-Key header is required",
                    "Send a key you made for this request, such as a UUID, of at most 255 characters.");
        }
        return switch (store.claim(scope, key, fingerprint(request))) {
            case Replay replay -> ResponseEntity.status(replay.response().status())
                    .contentType(MediaType.parseMediaType(replay.response().contentType()))
                    .body(replay.response().body());
            case Reused reused -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "/problems/idempotency-key-reused",
                    "This Idempotency-Key was used for a different request", "Use a new key for a new request.");
            case InProgress inProgress -> {
                long seconds = Math.max(1, (inProgress.retryAfter().toMillis() + 999) / 1000);
                ResponseEntity<String> problem = problem(HttpStatus.CONFLICT, "/problems/request-in-progress",
                        "A request with this Idempotency-Key is still being handled",
                        "Try again in " + seconds + " seconds.");
                yield ResponseEntity.status(problem.getStatusCode()).headers(problem.getHeaders())
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds)).body(problem.getBody());
            }
            case Act claimed -> act(store, scope, key, act, refusals);
        };
    }

    /**
     * The act and its stored response in one transaction. A refusal rolls the act back, then is stored on its
     * own: it changed nothing. An unexpected failure releases the key, so the client may try again at once; a
     * request that dies without releasing it is taken over after the request timeout.
     */
    private ResponseEntity<String> act(IdempotencyStore store, String scope, String key,
            Supplier<ResponseEntity<String>> act,
            Function<RuntimeException, Optional<ResponseEntity<String>>> refusals) {
        try {
            return transactions.execute(tx -> stored(store, scope, key, act.get()));
        } catch (RuntimeException failure) {
            Optional<ResponseEntity<String>> refusal = refusals.apply(failure);
            if (refusal.isEmpty()) {
                store.release(scope, key);
                throw failure;
            }
            return transactions.execute(tx -> stored(store, scope, key, refusal.get()));
        }
    }

    private static ResponseEntity<String> stored(IdempotencyStore store, String scope, String key,
            ResponseEntity<String> response) {
        store.complete(scope, key, new StoredResponse(response.getStatusCode().value(),
                response.getHeaders().getContentType().toString(), response.getBody()));
        return response;
    }

    @Override
    public ResponseEntity<String> problem(HttpStatus status, String type, String title, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(json.writeValueAsString(body));
    }

    /** What makes two requests "the same request" for one key: the SHA-256 of the body's JSON. */
    private String fingerprint(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }
}
