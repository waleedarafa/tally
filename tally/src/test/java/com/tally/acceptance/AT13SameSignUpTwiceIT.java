package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import java.time.ZoneId;
import java.util.Currency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * AT-13. The same sign-up sent twice with one idempotency key (ADR-019, D2). Pass when: one subscription,
 * and identical responses. A key reused for a different request is refused with a problem, and changes
 * nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AT13SameSignUpTwiceIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    AccountApi accounts;

    @Autowired
    JdbcTemplate jdbc;

    RestClient tally;

    @BeforeEach
    void client() {
        tally = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    ResponseEntity<String> signUp(String key, UUID account, String planId) {
        return tally.post().uri("/v1/subscriptions").header("Idempotency-Key", key)
                .body(Map.of("accountId", account, "planId", planId, "paymentToken", "tok_" + account))
                .retrieve().toEntity(String.class);
    }

    long subscriptions(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM subscription.subscription WHERE account_id = ?", Long.class,
                account);
    }

    @Test
    void theSameSignUpTwiceWithOneKeyMakesOneSubscriptionAndIdenticalResponses() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = signUp(key, account, "BASIC");
        ResponseEntity<String> second = signUp(key, account, "BASIC");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getContentType()).isEqualTo(first.getHeaders().getContentType());
        assertThat(subscriptions(account)).isEqualTo(1);
    }

    /**
     * Sent at the same moment (CLAUDE.md rule 7: duplicates arriving together): the key is claimed atomically,
     * so one request acts; the other gets the stored response or "still being handled" (409), never a second
     * subscription.
     */
    @Test
    void theSameSignUpTwiceAtOnceMakesOneSubscription() throws Exception {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<ResponseEntity<String>> send = () -> {
            together.await();
            return signUp(key, account, "BASIC");
        };
        ExecutorService two = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> sent = two.invokeAll(List.of(send, send));
            List<Integer> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> response : sent) {
                statuses.add(response.get().getStatusCode().value());
            }
            assertThat(statuses).contains(201).allMatch(status -> status == 201 || status == 409);
        } finally {
            two.shutdown();
        }
        assertThat(subscriptions(account)).isEqualTo(1);
        // Once the first is done, the key answers with its stored response.
        assertThat(signUp(key, account, "BASIC").getStatusCode().value()).isEqualTo(201);
    }

    /** Keys are scoped by account until callers are authenticated: one key value, two accounts, two sign-ups. */
    @Test
    void twoAccountsUsingTheSameKeyValueBothSucceed() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        UUID other = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = signUp(key, account, "BASIC");
        ResponseEntity<String> second = signUp(key, other, "BASIC");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isNotEqualTo(first.getBody());
        assertThat(subscriptions(account)).isEqualTo(1);
        assertThat(subscriptions(other)).isEqualTo(1);
    }

    @Test
    void aKeyReusedForADifferentRequestIsRefusedAndChangesNothing() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();
        signUp(key, account, "BASIC");

        ResponseEntity<String> reused = signUp(key, account, "PREMIUM");

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(reused.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(reused.getBody()).contains("\"type\":\"/problems/idempotency-key-reused\"");
        assertThat(subscriptions(account)).as("still the first sign-up only").isEqualTo(1);
    }
}
