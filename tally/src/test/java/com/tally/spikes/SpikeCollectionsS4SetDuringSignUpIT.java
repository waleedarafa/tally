package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
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
 * sign-up's read of the account and the sign-up's write of it gives the sign-up a 500. This pins today's answer, before
 * the fix in PR-4, which turns it into 409 with {@code Retry-After}.
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
    AccountStanding standing;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    ResponseEntity<String> signUp(UUID account) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }).build()
                .post().uri("/v1/subscriptions").header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("accountId", account, "planId", "BASIC", "paymentToken", "tok_s4_" + account))
                .retrieve().toEntity(String.class);
    }

    long sessionsWaitingOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'", Long.class);
    }

    @Test
    void aSetThatCommitsInsideASignUpsWindowGivesTheSignUp500Today() throws Exception {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        CompletableFuture<ResponseEntity<String>> signUp;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("LOCK TABLE subscription.subscription IN SHARE MODE");
            signUp = CompletableFuture.supplyAsync(() -> signUp(account));
            await().atMost(Duration.ofSeconds(20)).until(() -> sessionsWaitingOnALock() >= 1);
            System.out.println("SPIKE-C-S4 waiting: " + jdbc.queryForList("SELECT wait_event, query"
                    + " FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'"));

            // Collections' set, committed while the sign-up waits.
            standing.set(account, Standing.OVERDUE, "spike S-4");
            holder.rollback();
        }
        ResponseEntity<String> answer = signUp.get(20, TimeUnit.SECONDS);

        System.out.println("SPIKE-C-S4 answer: " + answer.getStatusCode().value() + " " + answer.getBody());
        assertThat(answer.getStatusCode().value()).as("today: the sign-up fails on the account's version")
                .isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.subscription WHERE account_id = ?",
                Long.class, account)).as("and nothing of it is kept").isZero();
        assertThat(accounts.get(account).orElseThrow().standing()).isEqualTo(Standing.OVERDUE);
    }
}
