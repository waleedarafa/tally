package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

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
        return signUp(key, account, planId, "tok_" + account);
    }

    ResponseEntity<String> signUp(String key, UUID account, String planId, String paymentToken) {
        var request = tally.post().uri("/v1/subscriptions");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.body(Map.of("accountId", account, "planId", planId, "paymentToken", paymentToken))
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

    /**
     * One person signs up in two browser tabs at once: one account, two keys, two tokens. Both requests read the
     * account before either commits: the test holds the account's row lock until both are waiting on a lock, so
     * neither can commit first, whichever order Hibernate writes in. One request subscribes; the other is told the
     * account already has a subscription. Account's version (an optimistic lock since Slice 2 PR-2) must not turn
     * the loser into a 500.
     *
     * <p>Observed (the {@code SIGN-UP-RACE} lines): the winner waits on the test's lock with its account update;
     * the loser waits with its subscription insert, on the winner's unique entry. Hibernate writes inserts before
     * updates, so the loser fails on {@code subscription_one_per_account} before its versioned account update
     * runs. A change that writes the account update first, such as a query in the act that flushes it early,
     * turns the loser into an optimistic-lock failure and a 500, and fails this test.
     */
    @Test
    void twoSignUpsForOneAccountAtTheSameTimeGiveOneSubscriptionAndOneAlreadySubscribed() throws Exception {
        UUID account = account();
        List<CompletableFuture<ResponseEntity<String>>> tabs;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM account.account WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, account);
                lock.executeQuery();
            }
            tabs = List.of(
                    CompletableFuture.supplyAsync(() -> signUp(UUID.randomUUID().toString(), account, "BASIC",
                            "tok_tab_1")),
                    CompletableFuture.supplyAsync(() -> signUp(UUID.randomUUID().toString(), account, "BASIC",
                            "tok_tab_2")));
            await().atMost(Duration.ofSeconds(20)).until(() -> sessionsWaitingOnALock() >= 2);
            System.out.println("SIGN-UP-RACE waiting: " + jdbc.queryForList("SELECT wait_event, query"
                    + " FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'"));
            holder.rollback();
        }
        List<ResponseEntity<String>> answers = List.of(tabs.get(0).get(20, TimeUnit.SECONDS),
                tabs.get(1).get(20, TimeUnit.SECONDS));

        System.out.println("SIGN-UP-RACE answers: " + answers.stream()
                .map(a -> a.getStatusCode().value() + " " + a.getBody()).toList());
        assertThat(answers).extracting(a -> a.getStatusCode().value()).containsExactlyInAnyOrder(201, 409);
        assertProblem(answers.stream().filter(a -> a.getStatusCode().value() == 409).findFirst().orElseThrow(), 409,
                "/problems/already-subscribed");
    }

    /** Sessions of this test class's database that wait on a lock (ADR-029: other classes have other databases). */
    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
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
