package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.support.TestTokens;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * AT-28. One account for each user (Slice 4 brief; WAR-84; ADR-032). A user opens an account, then opens one again
 * with a new key: 200, the same account. Two requests at the same time with two keys: one account. {@code GET
 * /v1/me/account} gives that account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AT28OneAccountForEachUserIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    RestClient as(TestTokens.Household household) {
        return household.client(RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }));
    }

    ResponseEntity<Map<String, Object>> open(TestTokens.Household household, String currency) {
        return as(household).post().uri("/v1/accounts").header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("currency", currency, "timeZone", "UTC", "country", "US"))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    ResponseEntity<Map<String, Object>> me(TestTokens.Household household) {
        return as(household).get().uri("/v1/me/account").retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    long accountsOf(String user) {
        return jdbc.queryForObject("SELECT count(*) FROM account.account_user WHERE user_id = ?", Long.class, user);
    }

    @Test
    void aSecondOpenWithANewKeyAnswers200WithTheSameAccount() {
        TestTokens.Household household = TestTokens.household();
        assertThat(me(household).getStatusCode().value()).as("before the user has an account").isEqualTo(404);

        ResponseEntity<Map<String, Object>> first = open(household, "USD");
        ResponseEntity<Map<String, Object>> second = open(household, "AED");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getBody()).containsEntry("currency", "USD");
        assertThat(me(household).getBody()).isEqualTo(first.getBody());
        assertThat(accountsOf(household.user())).isEqualTo(1);
    }

    @Test
    void twoUsersGetTwoAccounts() {
        TestTokens.Household a = TestTokens.household();
        TestTokens.Household b = TestTokens.household();

        Object accountA = open(a, "USD").getBody().get("id");
        Object accountB = open(b, "USD").getBody().get("id");

        assertThat(accountA).isNotEqualTo(accountB);
        assertThat(me(a).getBody()).containsEntry("id", accountA);
        assertThat(me(b).getBody()).containsEntry("id", accountB);
    }

    @Test
    void twoOpensAtTheSameTimeWithTwoKeysGiveOneAccount() throws Exception {
        TestTokens.Household household = TestTokens.household();

        CompletableFuture<ResponseEntity<Map<String, Object>>> one =
                CompletableFuture.supplyAsync(() -> open(household, "USD"));
        CompletableFuture<ResponseEntity<Map<String, Object>>> other =
                CompletableFuture.supplyAsync(() -> open(household, "USD"));

        ResponseEntity<Map<String, Object>> a = one.get(30, TimeUnit.SECONDS);
        ResponseEntity<Map<String, Object>> b = other.get(30, TimeUnit.SECONDS);
        assertThat(a.getBody().get("id")).isEqualTo(b.getBody().get("id"));
        assertThat(java.util.List.of(a.getStatusCode().value(), b.getStatusCode().value()))
                .containsAnyOf(201, 200).allMatch(status -> status == 201 || status == 200);
        assertThat(accountsOf(household.user())).isEqualTo(1);
        assertThat(me(household).getBody()).containsEntry("id", a.getBody().get("id"));
    }

    /**
     * The race made certain: another transaction links the user and has not committed when the request comes. The
     * request reads no link, opens an account, and its link waits on the unique constraint; the other transaction
     * commits, the request's insert fails on {@code account_user_one_per_user}, rolls back, and answers 200 with the
     * winner's account. No second account is kept.
     */
    @Test
    void theLoserOfTheRaceRollsBackAndAnswersTheWinnersAccount() throws Exception {
        TestTokens.Household household = TestTokens.household();
        UUID winner = UUID.randomUUID();
        long accountsBefore = jdbc.queryForObject("SELECT count(*) FROM account.account", Long.class);

        CompletableFuture<ResponseEntity<Map<String, Object>>> loser;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Timestamp now = Timestamp.from(Instant.parse("2026-01-15T10:00:00Z"));
            try (PreparedStatement account = connection.prepareStatement("INSERT INTO account.account"
                    + " (id, currency, time_zone, country, created_at, standing, version) VALUES (?, 'EUR', 'UTC', 'US', ?, 'GOOD', 0)");
                    PreparedStatement link = connection.prepareStatement("INSERT INTO account.account_user"
                            + " (account_id, user_id, linked_at) VALUES (?, ?, ?)")) {
                account.setObject(1, winner);
                account.setTimestamp(2, now);
                account.executeUpdate();
                link.setObject(1, winner);
                link.setString(2, household.user());
                link.setTimestamp(3, now);
                link.executeUpdate();
            }
            loser = CompletableFuture.supplyAsync(() -> open(household, "USD"));
            Thread.sleep(1_000);
            assertThat(loser).as("the request waits on the unique constraint").isNotDone();
            connection.commit();
        }

        ResponseEntity<Map<String, Object>> answer = loser.get(30, TimeUnit.SECONDS);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).containsEntry("id", winner.toString()).containsEntry("currency", "EUR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account.account", Long.class))
                .as("the loser's account is rolled back").isEqualTo(accountsBefore + 1);
        assertThat(accountsOf(household.user())).isEqualTo(1);
    }
}
