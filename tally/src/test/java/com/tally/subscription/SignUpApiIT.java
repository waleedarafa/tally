package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * {@code POST /v1/subscriptions} (design.md §8, ADR-019, docs/api/openapi.yaml): an Idempotency-Key is
 * required, and every error is an RFC 9457 problem with the type codes of design.md §8.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignUpApiIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    AccountApi accounts;

    @Autowired
    AccountStanding standing;

    RestClient tally;

    @BeforeEach
    void client() {
        tally = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    UUID account() {
        return accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
    }

    ResponseEntity<String> signUp(String key, UUID account, String planId) {
        var request = tally.post().uri("/v1/subscriptions");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.body(Map.of("accountId", account, "planId", planId, "paymentToken", "tok_" + account))
                .retrieve().toEntity(String.class);
    }

    static void assertProblem(ResponseEntity<String> response, int status, String type) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody()).contains("\"type\":\"" + type + "\"").contains("\"status\":" + status)
                .contains("\"title\":");
    }

    @Test
    void aSignUpWithoutAnIdempotencyKeyIsRefused() {
        assertProblem(signUp(null, account(), "BASIC"), 400, "about:blank");
    }

    @Test
    void aPlanNotOfferedIsOfferNotAvailable() {
        assertProblem(signUp(UUID.randomUUID().toString(), account(), "NO-SUCH-PLAN"), 422,
                "/problems/offer-not-available");
    }

    @Test
    void aSecondSubscriptionWithAnotherKeyIsAlreadySubscribed() {
        UUID account = account();
        assertThat(signUp(UUID.randomUUID().toString(), account, "BASIC").getStatusCode().value()).isEqualTo(201);

        assertProblem(signUp(UUID.randomUUID().toString(), account, "BASIC"), 409, "/problems/already-subscribed");
    }

    @Test
    void aBlockedAccountIsRefused() {
        UUID account = account();
        standing.set(account, Standing.BLOCKED, "SignUpApiIT");

        assertProblem(signUp(UUID.randomUUID().toString(), account, "BASIC"), 409, "/problems/account-blocked");
    }

    @Test
    void anUnknownAccountIsNotFound() {
        assertProblem(signUp(UUID.randomUUID().toString(), UUID.randomUUID(), "BASIC"), 404, "about:blank");
    }

    /** A refusal is stored with its key too: the same request again gets the same problem. */
    @Test
    void aRefusalIsReplayedForTheSameKey() {
        String key = UUID.randomUUID().toString();
        UUID account = account();
        ResponseEntity<String> first = signUp(key, account, "NO-SUCH-PLAN");

        ResponseEntity<String> again = signUp(key, account, "NO-SUCH-PLAN");

        assertThat(again.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(again.getBody()).isEqualTo(first.getBody());
    }
}
