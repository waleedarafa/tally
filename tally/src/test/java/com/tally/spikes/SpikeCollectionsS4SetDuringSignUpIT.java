package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import com.tally.support.TestAccounts;
import com.tally.support.TestTokens;
import java.sql.Connection;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Slice 3 spike S-4 (WAR-57; slice-2-report §4.4, limit 4): a {@code set} of the standing that commits between a
 * sign-up's read of the account and the sign-up's write of it. Before Slice 3 PR-4 the sign-up answered 500; since
 * PR-4 it answers 409 with {@code Retry-After}, and the key is not used up: the same request, sent again with the same
 * key, signs up (WAR-57, option a).
 *
 * <p>The window is held open by the test: it locks {@code subscription.subscription} in SHARE mode, which the sign-up's
 * insert waits for. The sign-up has then read the account (version 0), and its versioned update of the account comes
 * after the insert. The {@code set} commits while it waits (version 1); the lock is released; the update finds
 * version 1, changes no row, and fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpikeCollectionsS4SetDuringSignUpIT extends AbstractIntegrationTest {

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

    /** One household for each test: both requests of a race come from it. */
    final TestTokens.Household household = TestTokens.household();

    ResponseEntity<String> signUp(UUID account, String key) {
        return household.client(RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }))
                .post().uri("/v1/subscriptions").header("Idempotency-Key", key)
                .body(Map.of("accountId", account, "planId", "BASIC", "paymentToken", "tok_s4_" + account))
                .retrieve().toEntity(String.class);
    }

    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    @Test
    void aSetThatCommitsInsideASignUpsWindowGivesTheSignUp409WithRetryAfter() throws Exception {
        UUID account = testAccounts.openFor(household, Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        String key = UUID.randomUUID().toString();
        CompletableFuture<ResponseEntity<String>> signUp;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("LOCK TABLE subscription.subscription IN SHARE MODE");
            signUp = CompletableFuture.supplyAsync(() -> signUp(account, key));
            await().atMost(Duration.ofSeconds(20)).until(() -> sessionsWaitingOnALock() >= 1);
            System.out.println("SPIKE-C-S4 waiting: " + jdbc.queryForList("SELECT wait_event, query"
                    + " FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'"));

            // Collections' set, committed while the sign-up waits.
            standing.set(account, Standing.OVERDUE, "spike S-4");
            holder.rollback();
        }
        ResponseEntity<String> answer = signUp.get(20, TimeUnit.SECONDS);

        System.out.println("SPIKE-C-S4 answer: " + answer.getStatusCode().value() + " " + answer.getBody());
        assertThat(answer.getStatusCode().value()).as("the sign-up meets the account's new version").isEqualTo(409);
        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(answer.getBody()).contains("/problems/concurrent-change");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.subscription WHERE account_id = ?",
                Long.class, account)).as("and nothing of it is kept").isZero();
        assertThat(accounts.get(account).orElseThrow().standing()).isEqualTo(Standing.OVERDUE);

        ResponseEntity<String> again = signUp(account, key);
        System.out.println("SPIKE-C-S4 again, same key: " + again.getStatusCode().value());
        assertThat(again.getStatusCode().value()).as("the 409 was not stored: the same key signs up").isEqualTo(201);
    }
}
