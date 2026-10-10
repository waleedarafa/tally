package com.fakeprovider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * Spike S-2 (Slice 1R brief): the provider's idempotency keys, and the status of a request learnt by
 * key, modelled on Stripe's API v1. CONTRACT.md A22 and A23.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyKeysTest {

    @LocalServerPort
    int port;

    RestClient http;

    record Answer(int status, Map<String, Object> body) {
    }

    @BeforeEach
    void client() {
        http = RestClient.create("http://localhost:" + port);
        mode("NORMAL");
    }

    void mode(String... modes) {
        http.post().uri("/admin/mode").body(Map.of("modes", List.of(modes))).retrieve().toBodilessEntity();
    }

    String token() {
        return http.post().uri("/tokens").retrieve()
                .body(new ParameterizedTypeReference<Map<String, String>>() { }).get("token");
    }

    Answer charge(String key, String token, long amount, String reference) {
        return http.post().uri("/charges")
                .header("Idempotency-Key", key)
                .body(Map.of("token", token, "amount", amount, "currency", "USD", "reference", reference))
                .exchange((request, response) -> new Answer(response.getStatusCode().value(),
                        response.getHeaders().getContentLength() == 0 ? Map.of()
                                : response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() { })));
    }

    int charges(String reference) {
        return http.get().uri("/admin/charges/count?reference={r}", reference).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Integer>>() { }).get("count");
    }

    static String reference() {
        return "ref-" + UUID.randomUUID();
    }

    @Test
    void theSameKeyTwiceChargesOnceAndReplaysTheFirstResponse() {
        String token = token();
        String reference = reference();

        Answer first = charge("key-1-" + reference, token, 999, reference);
        Answer repeat = charge("key-1-" + reference, token, 999, reference);

        assertThat(first.status()).isEqualTo(200);
        assertThat(repeat).isEqualTo(first);
        assertThat(charges(reference)).isEqualTo(1);
    }

    /** Why each new attempt needs its own key: the first key replays its decline, even after the card is fixed. */
    @Test
    void aDeclineIsReplayedForTheSameKeyAndANewKeyChargesAgain() {
        String token = token();
        String reference = reference();
        mode("DECLINE_SOFT(100)");
        Answer declined = charge(reference + "-1", token, 999, reference);
        mode("NORMAL");

        Answer replayed = charge(reference + "-1", token, 999, reference);
        Answer secondAttempt = charge(reference + "-2", token, 999, reference);

        assertThat(declined.body()).containsEntry("outcome", "DECLINED_SOFT");
        assertThat(replayed).isEqualTo(declined);
        assertThat(secondAttempt.body()).containsEntry("outcome", "SUCCEEDED");
        assertThat(charges(reference)).isEqualTo(2);
    }

    @Test
    void theSameKeyWithOtherParametersIsRefusedAndChargesNothing() {
        String token = token();
        String reference = reference();
        charge(reference, token, 999, reference);

        Answer other = charge(reference, token, 1999, reference);

        assertThat(other.status()).isEqualTo(400);
        assertThat(other.body()).containsEntry("error", "idempotency_error");
        assertThat(charges(reference)).isEqualTo(1);
    }

    /** Status by key: after a timeout, repeating the request with its key tells the outcome without charging. */
    @Test
    void afterATimeoutTheSameKeyReturnsTheRecordedChargeWithoutChargingAgain() {
        String token = token();
        String reference = reference();
        mode("TIMEOUT_AFTER_CHARGE");
        CompletableFuture<Answer> late = CompletableFuture.supplyAsync(() -> charge(reference, token, 999, reference));
        waitForCharge(reference);
        mode("NORMAL");

        Answer status = charge(reference, token, 999, reference);

        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body()).containsEntry("outcome", "SUCCEEDED");
        assertThat(late.join()).isEqualTo(status);
        assertThat(charges(reference)).isEqualTo(1);
    }

    /** Refusals in front of the idempotency layer (503 outage, 429 rate limit) are not saved. */
    @Test
    void aRefusedRequestIsNotSavedSoTheSameKeyChargesLater() {
        String token = token();
        String reference = reference();
        mode("OUTAGE");
        Answer refused = charge(reference, token, 999, reference);
        mode("RATE_LIMIT(1)");
        charge("other-" + reference, token, 999, "other-" + reference);
        Answer limited = charge(reference, token, 999, reference);
        mode("NORMAL");

        Answer later = charge(reference, token, 999, reference);

        assertThat(refused.status()).isEqualTo(503);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(later.status()).isEqualTo(200);
        assertThat(charges(reference)).isEqualTo(1);
    }

    @Test
    void aRepeatWhileTheFirstStillExecutesIsAConflictAndIsNotSaved() {
        String token = token();
        String reference = reference();
        mode("SLOW(1500)");
        CompletableFuture<Answer> first = CompletableFuture.supplyAsync(() -> charge(reference, token, 999, reference));
        waitForCharge(reference);

        Answer concurrent = charge(reference, token, 999, reference);

        assertThat(concurrent.status()).isEqualTo(409);
        assertThat(first.join().status()).isEqualTo(200);
        assertThat(charge(reference, token, 999, reference)).isEqualTo(first.join());
        assertThat(charges(reference)).isEqualTo(1);
    }

    /** After the retention period (24 h, simulated) a reused key is a new request: it charges again. */
    @Test
    void afterRetentionAReusedKeyChargesAgain() {
        String token = token();
        String reference = reference();
        charge(reference, token, 999, reference);

        http.post().uri("/admin/idempotency-keys/prune").retrieve().toBodilessEntity();
        Answer reused = charge(reference, token, 999, reference);

        assertThat(reused.status()).isEqualTo(200);
        assertThat(charges(reference)).isEqualTo(2);
    }

    void waitForCharge(String reference) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (charges(reference) == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(charges(reference)).isEqualTo(1);
    }
}
