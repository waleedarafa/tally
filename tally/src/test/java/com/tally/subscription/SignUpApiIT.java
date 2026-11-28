package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import com.tally.support.TestAccounts;
import com.tally.support.TestTokens;
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
    TestAccounts testAccounts;

    @Autowired
    AccountStanding standing;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    RestClient tally;

    /** The household of each test: it opens the test's account and sends every request (Slice 4 PR-4). */
    TestTokens.Household household;

    @BeforeEach
    void client() {
        household = TestTokens.household();
        tally = household.client(RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }));
    }

    UUID account() {
        return testAccounts.openFor(household, Currency.getInstance("USD"), ZoneId.of("UTC")).id();
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
    void aSecondSubscriptionWithAnotherKeyIsCreated() {
        UUID account = account();
        ResponseEntity<String> first = signUp(UUID.randomUUID().toString(), account, "BASIC");
        ResponseEntity<String> second = signUp(UUID.randomUUID().toString(), account, "BASIC");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isNotEqualTo(first.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.subscription WHERE account_id = ?",
                Long.class, account)).isEqualTo(2);
    }

    /**
     * One person signs up in two browser tabs at once: one account, two keys, two tokens (ADR-046). Both
     * requests take the account's row lock when they record the payment token. The test holds that lock
     * until both are waiting, then releases it: the two writes then run one after the other, both
     * subscribe, and neither fails as {@code /problems/concurrent-change}.
     */
    @Test
    void twoSignUpsForOneAccountAtTheSameTimeBothSucceed() throws Exception {
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
        assertThat(answers).extracting(a -> a.getStatusCode().value()).containsExactlyInAnyOrder(201, 201);
        assertThat(answers.get(0).getBody()).isNotEqualTo(answers.get(1).getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.subscription WHERE account_id = ?",
                Long.class, account)).isEqualTo(2);
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
